package burpmcp.transport;

import burpmcp.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The modern (2025-06-18) single-endpoint MCP transport. Each {@link #send} is its own POST;
 * the server replies with either a single JSON object or a {@code text/event-stream} upgrade
 * carrying intermediate notifications before the final response. Concurrency for multiple
 * in-flight calls comes from callers invoking {@link #send} from different threads — this class
 * itself is stateless per-call beyond the negotiated session id/protocol version.
 */
public final class StreamableHttpTransport implements McpTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final URI endpoint;
    private final Map<String, String> customHeaders;
    private final TokenSupplier tokenSupplier;
    private final HttpClient httpClient;

    private volatile TransportListener listener;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private volatile String protocolVersion;

    public StreamableHttpTransport(URI endpoint, Map<String, String> customHeaders, TokenSupplier tokenSupplier, HttpClient httpClient) {
        this.endpoint = endpoint;
        this.customHeaders = customHeaders;
        this.tokenSupplier = tokenSupplier;
        this.httpClient = httpClient;
    }

    @Override
    public void connect(TransportListener listener) {
        this.listener = listener;
    }

    @Override
    public void send(String jsonRpcPayload) throws TransportException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcPayload, StandardCharsets.UTF_8));

        customHeaders.forEach(builder::header);
        tokenSupplier.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
        String currentSession = sessionId.get();
        if (currentSession != null) {
            builder.header("Mcp-Session-Id", currentSession);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }

        HttpRequest request = builder.build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            throw new TransportException("Failed to reach " + endpoint + ": " + e.getMessage(), e);
        }

        String newSessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (newSessionId != null) {
            sessionId.set(newSessionId);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        logExchange(request, jsonRpcPayload, response, contentType);

        int status = response.statusCode();
        if (status == 401) {
            String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse(null);
            drain(response.body());
            throw new AuthRequiredException(wwwAuth, false);
        }
        if (status == 403) {
            String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse(null);
            boolean insufficientScope = wwwAuth != null && wwwAuth.contains("insufficient_scope");
            drain(response.body());
            if (insufficientScope) {
                throw new AuthRequiredException(wwwAuth, true);
            }
            throw new TransportException("Forbidden (403) calling " + endpoint);
        }
        if (status == 404 || status == 405 || status == 406) {
            drain(response.body());
            throw new TransportUnsupportedException(
                    "Server responded " + status + " to a Streamable HTTP POST — likely not a Streamable HTTP endpoint");
        }
        if (status == 202) {
            drain(response.body());
            return;
        }
        if (status < 200 || status >= 300) {
            String body = readAll(response.body());
            throw new TransportException("MCP server returned HTTP " + status + ": " + truncate(body));
        }

        if (contentType.startsWith("text/event-stream")) {
            readSseStream(response.body());
        } else {
            String body = readAll(response.body());
            if (!body.isBlank()) {
                deliver(body);
            }
        }
    }

    private void readSseStream(InputStream body) throws TransportException {
        SseEventParser parser = new SseEventParser();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.feedLine(line).ifPresent(event -> {
                    if (!event.data().isBlank()) {
                        deliver(event.data());
                    }
                });
            }
        } catch (IOException e) {
            throw new TransportException("SSE stream read failed: " + e.getMessage(), e);
        }
    }

    private void deliver(String rawJson) {
        TransportListener l = listener;
        if (l != null) {
            try {
                l.onMessage(rawJson);
            } catch (Exception e) {
                Log.error("TransportListener.onMessage threw", e);
            }
        }
    }

    private void logExchange(HttpRequest request, String requestBody, HttpResponse<?> response, String contentType) {
        TransportListener l = listener;
        if (l == null) {
            return;
        }
        Map<String, java.util.List<String>> reqHeaders = new HashMap<>(request.headers().map());
        l.onRawHttpExchange(new HttpExchangeLog(
                "MCP",
                request.method(),
                request.uri().toString(),
                reqHeaders,
                requestBody,
                response.statusCode(),
                new HashMap<>(response.headers().map()),
                contentType
        ));
    }

    @Override
    public void close() {
        String sid = sessionId.get();
        if (sid == null) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Mcp-Session-Id", sid)
                    .method("DELETE", HttpRequest.BodyPublishers.noBody());
            customHeaders.forEach(builder::header);
            tokenSupplier.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Log.error("Failed to send session-termination DELETE (non-fatal)", e);
        }
        TransportListener l = listener;
        if (l != null) {
            l.onClosed("Client disconnected");
        }
    }

    @Override
    public TransportKind kind() {
        return TransportKind.STREAMABLE_HTTP;
    }

    @Override
    public Optional<String> sessionId() {
        return Optional.ofNullable(sessionId.get());
    }

    @Override
    public void setProtocolVersion(String version) {
        this.protocolVersion = version;
    }

    private static void drain(InputStream in) {
        try {
            in.readAllBytes();
        } catch (IOException ignored) {
        }
    }

    private static String readAll(InputStream in) throws TransportException {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TransportException("Failed to read response body: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
