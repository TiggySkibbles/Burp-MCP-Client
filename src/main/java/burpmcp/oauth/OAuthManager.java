package burpmcp.oauth;

import burpmcp.persistence.CachedDiscovery;
import burpmcp.persistence.PersistenceService;
import burpmcp.persistence.StoredTokens;
import burpmcp.transport.HttpExchangeLog;
import burpmcp.util.Json;
import burpmcp.util.Log;
import com.fasterxml.jackson.databind.JsonNode;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the full MCP OAuth 2.1 flow: RFC 9728 / RFC 8414 discovery, RFC 7591 Dynamic Client
 * Registration (or a user-supplied static client id), PKCE, a loopback redirect listener, and
 * token exchange/refresh/storage. Every discovery/DCR/token HTTP call is also pushed to the
 * traffic log, tagged "OAuth", since a pentester will want to see exactly what was discovered.
 */
public final class OAuthManager {

    private static final Pattern RESOURCE_METADATA_PATTERN = Pattern.compile("resource_metadata=\"([^\"]+)\"");
    private static final String CLIENT_NAME = "Burp MCP Client";

    private final HttpClient httpClient;
    private final PersistenceService persistence;
    private final TrafficLogger trafficLogger;

    /** Minimal shim so OAuthManager doesn't need to depend on the whole protocol.TrafficSink surface. */
    public interface TrafficLogger {
        void onHttpExchange(HttpExchangeLog log);
    }

    public OAuthManager(HttpClient httpClient, PersistenceService persistence, TrafficLogger trafficLogger) {
        this.httpClient = httpClient;
        this.persistence = persistence;
        this.trafficLogger = trafficLogger;
    }

    /** Synchronous — checks cache, refreshes if expired. Never throws; returns empty if no valid token is available. Call off the EDT. */
    public Optional<String> currentBearerToken(URI resourceUri) {
        String key = burpmcp.oauth.CanonicalUrl.keyFor(resourceUri);
        StoredTokens tokens = persistence.loadTokens(key);
        if (tokens == null) {
            return Optional.empty();
        }
        if (!tokens.isExpired()) {
            return Optional.of(tokens.accessToken());
        }
        if (tokens.refreshToken() == null) {
            return Optional.empty();
        }
        try {
            StoredTokens refreshed = refresh(resourceUri, tokens);
            persistence.saveTokens(key, refreshed);
            return Optional.of(refreshed.accessToken());
        } catch (Exception e) {
            Log.error("Token refresh failed for " + resourceUri + " — will need a fresh sign-in", e);
            return Optional.empty();
        }
    }

    public AuthDiagnostics currentDiagnostics(URI resourceUri) {
        String key = burpmcp.oauth.CanonicalUrl.keyFor(resourceUri);
        StoredTokens tokens = persistence.loadTokens(key);
        CachedDiscovery discovery = persistence.loadDiscovery(key);
        if (tokens == null && discovery == null) {
            return AuthDiagnostics.NONE;
        }
        String issuer = null;
        String authEndpoint = null;
        String tokenEndpoint = null;
        String clientId = discovery != null ? discovery.registeredClientId() : null;
        if (discovery != null && discovery.authServerMetadataRawJson() != null) {
            try {
                AuthorizationServerMetadata asMeta = Json.MAPPER.readValue(discovery.authServerMetadataRawJson(), AuthorizationServerMetadata.class);
                issuer = asMeta.issuer();
                authEndpoint = asMeta.authorizationEndpoint();
                tokenEndpoint = asMeta.tokenEndpoint();
            } catch (Exception ignored) {
            }
        }
        return new AuthDiagnostics(
                tokens != null,
                tokens != null && tokens.isExpired(),
                clientId,
                tokens != null ? tokens.scope() : null,
                tokens != null ? tokens.expiresAtEpochMillis() : null,
                authEndpoint,
                tokenEndpoint,
                issuer
        );
    }

    public void clearState(URI resourceUri) {
        String key = burpmcp.oauth.CanonicalUrl.keyFor(resourceUri);
        persistence.clearTokens(key);
    }

    /**
     * Full discovery -> (DCR or static client) -> PKCE -> loopback -> browser -> token exchange flow.
     * Must be called off the EDT; opens the user's system browser as a side effect.
     */
    public CompletableFuture<Void> startAuthorizationFlow(URI resourceUri, String wwwAuthenticateHeader, String staticClientId, String staticClientSecret) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            URI resourceMetadataUrl = resolveResourceMetadataUrl(wwwAuthenticateHeader, resourceUri);
            ProtectedResourceMetadata resourceMetadata = fetchJson(resourceMetadataUrl, ProtectedResourceMetadata.class);
            if (resourceMetadata.authorizationServers() == null || resourceMetadata.authorizationServers().isEmpty()) {
                throw new OAuthException("Protected resource metadata at " + resourceMetadataUrl + " lists no authorization_servers");
            }
            String authServerBase = resourceMetadata.authorizationServers().get(0);
            URI asMetadataUrl = URI.create(discoveryUrlFor(authServerBase));
            AuthorizationServerMetadata asMetadata = fetchJson(asMetadataUrl, AuthorizationServerMetadata.class);

            String clientId;
            String clientSecret;
            if (staticClientId != null && !staticClientId.isBlank()) {
                clientId = staticClientId;
                clientSecret = staticClientSecret;
            } else if (asMetadata.supportsDynamicClientRegistration()) {
                DynamicClientRegistration.RegisteredClient registered = performDcr(asMetadata.registrationEndpoint());
                clientId = registered.clientId();
                clientSecret = registered.clientSecret();
            } else {
                throw new OAuthException("Authorization server does not support Dynamic Client Registration and no client id was configured for this profile — set one in the connection settings");
            }

            PkceUtil.Pkce pkce = PkceUtil.generate();
            String state = PkceUtil.randomState();

            LoopbackCallbackServer callbackServer = new LoopbackCallbackServer();
            String redirectUri = callbackServer.redirectUri();
            String canonicalResource = burpmcp.oauth.CanonicalUrl.canonicalize(resourceUri);

            String authorizationUrl = buildAuthorizationUrl(asMetadata.authorizationEndpoint(), clientId, redirectUri, state, pkce, canonicalResource, resourceMetadata.scopesSupported());

            Desktop.getDesktop().browse(URI.create(authorizationUrl));

            String finalClientId = clientId;
            String finalClientSecret = clientSecret;
            AuthorizationServerMetadata finalAsMetadata = asMetadata;
            ProtectedResourceMetadata finalResourceMetadata = resourceMetadata;

            callbackServer.awaitCallback()
                    .orTimeout(5, TimeUnit.MINUTES)
                    .whenComplete((callback, throwable) -> {
                        callbackServer.close();
                        if (throwable != null) {
                            result.completeExceptionally(new OAuthException("Timed out waiting for the browser redirect", throwable));
                            return;
                        }
                        try {
                            if (callback.isError()) {
                                throw new OAuthException("Authorization server denied the request: " + callback.error()
                                        + (callback.errorDescription() != null ? " — " + callback.errorDescription() : ""));
                            }
                            if (!state.equals(callback.state())) {
                                throw new OAuthException("OAuth 'state' mismatch on callback — possible CSRF, aborting");
                            }
                            if (callback.code() == null) {
                                throw new OAuthException("OAuth callback had no authorization code");
                            }
                            StoredTokens tokens = exchangeCodeForTokens(finalAsMetadata.tokenEndpoint(), callback.code(),
                                    redirectUri, finalClientId, finalClientSecret, pkce.verifier(), canonicalResource);

                            String key = burpmcp.oauth.CanonicalUrl.keyFor(resourceUri);
                            persistence.saveTokens(key, tokens);
                            persistence.saveDiscovery(key, new CachedDiscovery(
                                    Json.MAPPER.writeValueAsString(finalResourceMetadata),
                                    Json.MAPPER.writeValueAsString(finalAsMetadata),
                                    finalClientId,
                                    finalClientSecret,
                                    System.currentTimeMillis()
                            ));
                            result.complete(null);
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                    });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    // ---- Discovery helpers ------------------------------------------------------------------

    private URI resolveResourceMetadataUrl(String wwwAuthenticateHeader, URI resourceUri) {
        if (wwwAuthenticateHeader != null) {
            Matcher m = RESOURCE_METADATA_PATTERN.matcher(wwwAuthenticateHeader);
            if (m.find()) {
                return URI.create(m.group(1));
            }
        }
        return resourceUri.resolve("/.well-known/oauth-protected-resource");
    }

    /** RFC 8414 §3.1: for an issuer with a path component, the well-known segment is inserted before the path. */
    private static String discoveryUrlFor(String issuerOrBase) {
        URI uri = URI.create(issuerOrBase);
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return uri.getScheme() + "://" + uri.getAuthority() + "/.well-known/oauth-authorization-server";
        }
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        return uri.getScheme() + "://" + uri.getAuthority() + "/.well-known/oauth-authorization-server" + cleanPath;
    }

    private <T> T fetchJson(URI url, Class<T> type) throws OAuthException {
        try {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            logExchange("OAuth", request, null, response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OAuthException("GET " + url + " returned HTTP " + response.statusCode());
            }
            return Json.MAPPER.readValue(response.body(), type);
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthException("Failed to fetch/parse " + url + ": " + e.getMessage(), e);
        }
    }

    private DynamicClientRegistration.RegisteredClient performDcr(String registrationEndpoint) throws OAuthException {
        try {
            String body = DynamicClientRegistration.buildRequestBody(CLIENT_NAME, "http://127.0.0.1/oauth/callback");
            HttpRequest request = HttpRequest.newBuilder(URI.create(registrationEndpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            logExchange("OAuth", request, body, response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OAuthException("Dynamic Client Registration at " + registrationEndpoint + " returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return DynamicClientRegistration.parseResponse(response.body());
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthException("Dynamic Client Registration failed: " + e.getMessage(), e);
        }
    }

    private String buildAuthorizationUrl(String authorizationEndpoint, String clientId, String redirectUri, String state,
                                          PkceUtil.Pkce pkce, String canonicalResource, List<String> scopes) {
        StringBuilder url = new StringBuilder(authorizationEndpoint);
        url.append(authorizationEndpoint.contains("?") ? "&" : "?");
        appendParam(url, "response_type", "code");
        appendParam(url, "client_id", clientId);
        appendParam(url, "redirect_uri", redirectUri);
        appendParam(url, "state", state);
        appendParam(url, "code_challenge", pkce.challenge());
        appendParam(url, "code_challenge_method", pkce.method());
        appendParam(url, "resource", canonicalResource);
        if (scopes != null && !scopes.isEmpty()) {
            appendParam(url, "scope", String.join(" ", scopes));
        }
        return url.toString();
    }

    private StoredTokens exchangeCodeForTokens(String tokenEndpoint, String code, String redirectUri, String clientId,
                                                String clientSecret, String codeVerifier, String canonicalResource) throws OAuthException {
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", codeVerifier);
        form.put("resource", canonicalResource);
        if (clientSecret != null) {
            form.put("client_secret", clientSecret);
        }
        return postTokenRequest(tokenEndpoint, form);
    }

    private StoredTokens refresh(URI resourceUri, StoredTokens current) throws OAuthException {
        String key = burpmcp.oauth.CanonicalUrl.keyFor(resourceUri);
        CachedDiscovery discovery = persistence.loadDiscovery(key);
        if (discovery == null || discovery.authServerMetadataRawJson() == null) {
            throw new IllegalStateException("No cached discovery to refresh against — a fresh sign-in is required");
        }
        try {
            AuthorizationServerMetadata asMeta = Json.MAPPER.readValue(discovery.authServerMetadataRawJson(), AuthorizationServerMetadata.class);
            Map<String, String> form = new HashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", current.refreshToken());
            form.put("client_id", discovery.registeredClientId());
            form.put("resource", burpmcp.oauth.CanonicalUrl.canonicalize(resourceUri));
            if (discovery.registeredClientSecret() != null) {
                form.put("client_secret", discovery.registeredClientSecret());
            }
            StoredTokens refreshed = postTokenRequest(asMeta.tokenEndpoint(), form);
            // Servers may omit refresh_token on refresh, meaning "reuse the same one".
            if (refreshed.refreshToken() == null && current.refreshToken() != null) {
                refreshed = new StoredTokens(refreshed.accessToken(), current.refreshToken(), refreshed.expiresAtEpochMillis(), refreshed.scope(), refreshed.tokenType());
            }
            return refreshed;
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthException("Refresh failed: " + e.getMessage(), e);
        }
    }

    private StoredTokens postTokenRequest(String tokenEndpoint, Map<String, String> form) throws OAuthException {
        String body = encodeForm(form);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // Not redacted: the traffic log intentionally shows secrets/tokens in full, since this
            // is a security-testing tool and users expect to see everything that went over the wire.
            logExchange("OAuth", request, body, response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OAuthException("Token endpoint " + tokenEndpoint + " returned HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = Json.MAPPER.readTree(response.body());
            String accessToken = json.path("access_token").asText(null);
            if (accessToken == null) {
                throw new OAuthException("Token response missing access_token");
            }
            String refreshToken = json.hasNonNull("refresh_token") ? json.get("refresh_token").asText() : null;
            long expiresInSeconds = json.path("expires_in").asLong(3600);
            String scope = json.hasNonNull("scope") ? json.get("scope").asText() : null;
            String tokenType = json.path("token_type").asText("Bearer");
            long expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000;
            return new StoredTokens(accessToken, refreshToken, expiresAt, scope, tokenType);
        } catch (OAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthException("Token request to " + tokenEndpoint + " failed: " + e.getMessage(), e);
        }
    }

    private void logExchange(String tag, HttpRequest request, String requestBody, HttpResponse<String> response) {
        if (trafficLogger == null) {
            return;
        }
        trafficLogger.onHttpExchange(new HttpExchangeLog(
                tag,
                request.method(),
                request.uri().toString(),
                new HashMap<>(request.headers().map()),
                requestBody,
                response.statusCode(),
                new HashMap<>(response.headers().map()),
                response.headers().firstValue("Content-Type").orElse("")
        ));
    }

    private static void appendParam(StringBuilder url, String key, String value) {
        if (url.charAt(url.length() - 1) != '?' && url.charAt(url.length() - 1) != '&') {
            url.append('&');
        }
        url.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
