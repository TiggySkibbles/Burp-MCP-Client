package burpmcp.transport;

import burpmcp.protocol.McpSession;
import burpmcp.protocol.MessageRouter;
import burpmcp.protocol.TrafficSink;
import burpmcp.protocol.model.ClientInfo;
import burpmcp.protocol.model.InitializeResult;
import burpmcp.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportDetectorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void detectsStreamableHttpWhenPostInitializeSucceeds() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var requestNode = Json.MAPPER.readTree(body);
            if (!requestNode.has("id")) {
                // notifications/initialized
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String id = requestNode.get("id").asText();
            String response = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{"
                    + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"streamable-test-server\",\"version\":\"1.0\"}}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        McpSession session = new McpSession(new MessageRouter(), TrafficSink.NOOP);
        InitializeResult result = TransportDetector.connectAndInitialize(
                session, serverUri(), Map.of(), TokenSupplier.NONE, HttpClient.newHttpClient(),
                new ClientInfo("test-client", "Test Client", "0.1"));

        assertEquals("streamable-test-server", result.serverInfo().name());
        assertEquals(TransportKind.STREAMABLE_HTTP, session.transport().kind());
        session.close();
    }

    @Test
    void fallsBackToLegacySseWhenPostIsRejected() throws Exception {
        CompletableFuture<Void> okToCloseStream = new CompletableFuture<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/mcp", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            // GET: open the legacy SSE stream.
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            writeSseEvent(os, "endpoint", "/messages");
            os.flush();

            server.createContext("/messages", postExchange -> {
                String body = new String(postExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var requestNode = Json.MAPPER.readTree(body);
                postExchange.sendResponseHeaders(202, -1);
                postExchange.close();

                if (requestNode.has("id")) {
                    String id = requestNode.get("id").asText();
                    String response = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{"
                            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                            + "\"serverInfo\":{\"name\":\"legacy-test-server\",\"version\":\"1.0\"}}}";
                    try {
                        writeSseEvent(os, null, response);
                        os.flush();
                    } catch (IOException ignored) {
                    }
                }
            });

            try {
                okToCloseStream.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            exchange.close();
        });
        server.start();

        McpSession session = new McpSession(new MessageRouter(), TrafficSink.NOOP);
        try {
            InitializeResult result = TransportDetector.connectAndInitialize(
                    session, serverUri(), Map.of(), TokenSupplier.NONE, HttpClient.newHttpClient(),
                    new ClientInfo("test-client", "Test Client", "0.1"));

            assertEquals("legacy-test-server", result.serverInfo().name());
            assertEquals(TransportKind.LEGACY_SSE, session.transport().kind());
        } finally {
            okToCloseStream.complete(null);
            session.close();
        }
    }

    private URI serverUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    private static void writeSseEvent(OutputStream os, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (event != null) {
            sb.append("event: ").append(event).append('\n');
        }
        sb.append("data: ").append(data).append('\n').append('\n');
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
