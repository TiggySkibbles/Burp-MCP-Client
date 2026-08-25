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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The deprecated (2024-11-05) two-endpoint transport: a long-lived GET/SSE stream whose first
 * event ({@code event: endpoint}) names the URL all client messages must be POSTed to; replies
 * arrive asynchronously as {@code message} events on the same GET stream.
 */
public final class LegacySseTransport implements McpTransport {

    private final URI sseEndpoint;
    private final Map<String, String> customHeaders;
    private final TokenSupplier tokenSupplier;
    private final HttpClient httpClient;

    private volatile TransportListener listener;
    private volatile URI postEndpoint;
    private volatile Throwable connectError;
    private volatile boolean closed;
    private final CountDownLatch endpointLatch = new CountDownLatch(1);

    public LegacySseTransport(URI sseEndpoint, Map<String, String> customHeaders, TokenSupplier tokenSupplier, HttpClient httpClient) {
        this.sseEndpoint = sseEndpoint;
        this.customHeaders = customHeaders;
        this.tokenSupplier = tokenSupplier;
        this.httpClient = httpClient;
    }

    @Override
    public void connect(TransportListener listener) throws TransportException {
        this.listener = listener;

        Thread readerThread = new Thread(this::runReadLoop, "mcp-legacy-sse-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        boolean gotEndpoint;
        try {
            gotEndpoint = endpointLatch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted waiting for legacy SSE 'endpoint' event");
        }
        if (connectError != null) {
            throw new TransportUnsupportedException("Legacy HTTP+SSE probe failed: " + connectError.getMessage());
        }
        if (!gotEndpoint || postEndpoint == null) {
            throw new TransportUnsupportedException("No 'endpoint' SSE event received within timeout — not a legacy HTTP+SSE server");
        }
    }

    private void runReadLoop() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(sseEndpoint)
                .header("Accept", "text/event-stream")
                .GET();
        customHeaders.forEach(builder::header);
        tokenSupplier.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
        HttpRequest request = builder.build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            logExchange(request, null, response, contentType);

            if (response.statusCode() != 200 || !contentType.startsWith("text/event-stream")) {
                connectError = new IOException("GET returned status " + response.statusCode() + ", content-type '" + contentType + "'");
                drain(response.body());
                endpointLatch.countDown();
                return;
            }

            SseEventParser parser = new SseEventParser();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    parser.feedLine(line).ifPresent(this::handleEvent);
                }
            }
            if (!closed) {
                TransportListener l = listener;
                if (l != null) {
                    l.onClosed("Server closed the SSE stream");
                }
            }
        } catch (Exception e) {
            connectError = e;
            endpointLatch.countDown();
            if (!closed) {
                TransportListener l = listener;
                if (l != null) {
                    l.onError(e);
                }
            }
        }
    }

    private void handleEvent(SseEvent event) {
        if ("endpoint".equals(event.event())) {
            if (postEndpoint == null) {
                postEndpoint = resolveEndpoint(event.data());
                endpointLatch.countDown();
            }
            return;
        }
        if (!event.data().isBlank()) {
            TransportListener l = listener;
            if (l != null) {
                try {
                    l.onMessage(event.data());
                } catch (Exception e) {
                    Log.error("TransportListener.onMessage threw", e);
                }
            }
        }
    }

    private URI resolveEndpoint(String data) {
        URI dataUri = URI.create(data.trim());
        return dataUri.isAbsolute() ? dataUri : sseEndpoint.resolve(dataUri);
    }

    @Override
    public void send(String jsonRpcPayload) throws TransportException {
        URI target = postEndpoint;
        if (target == null) {
            throw new TransportException("Legacy SSE transport not connected — no endpoint captured yet");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcPayload, StandardCharsets.UTF_8));
        customHeaders.forEach(builder::header);
        tokenSupplier.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
        HttpRequest request = builder.build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            throw new TransportException("Failed POST to legacy endpoint " + target + ": " + e.getMessage(), e);
        }
        logExchange(request, jsonRpcPayload, response, response.headers().firstValue("Content-Type").orElse(""));

        int status = response.statusCode();
        if (status == 401) {
            throw new AuthRequiredException(response.headers().firstValue("WWW-Authenticate").orElse(null), false);
        }
        if (status == 403) {
            String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse(null);
            boolean insufficientScope = wwwAuth != null && wwwAuth.contains("insufficient_scope");
            if (insufficientScope) {
                throw new AuthRequiredException(wwwAuth, true);
            }
            throw new TransportException("Forbidden (403) POSTing to " + target);
        }
        if (status < 200 || status >= 300) {
            throw new TransportException("Legacy SSE POST returned HTTP " + status + ": " + truncate(response.body()));
        }
        // The POST's own body is typically an empty ack; the actual JSON-RPC reply arrives as a
        // "message" SSE event on the long-lived GET stream, delivered via handleEvent().
    }

    private void logExchange(HttpRequest request, String requestBody, HttpResponse<?> response, String contentType) {
        TransportListener l = listener;
        if (l == null) {
            return;
        }
        l.onRawHttpExchange(new HttpExchangeLog(
                "MCP",
                request.method(),
                request.uri().toString(),
                new HashMap<>(request.headers().map()),
                requestBody,
                response.statusCode(),
                new HashMap<>(response.headers().map()),
                contentType
        ));
    }

    @Override
    public void close() {
        closed = true;
        TransportListener l = listener;
        if (l != null) {
            l.onClosed("Client disconnected");
        }
    }

    @Override
    public TransportKind kind() {
        return TransportKind.LEGACY_SSE;
    }

    @Override
    public Optional<String> sessionId() {
        return Optional.empty();
    }

    private static void drain(InputStream in) {
        try {
            in.readAllBytes();
        } catch (IOException ignored) {
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
