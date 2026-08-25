package burpmcp.protocol;

import burpmcp.transport.HttpExchangeLog;
import burpmcp.transport.McpTransport;
import burpmcp.transport.TransportException;
import burpmcp.transport.TransportKind;
import burpmcp.transport.TransportListener;
import burpmcp.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcCorrelationTest {

    /** Records every payload handed to send(); does no real I/O. */
    private static final class FakeTransport implements McpTransport {
        final List<String> sent = new ArrayList<>();
        TransportListener listener;

        @Override
        public void connect(TransportListener listener) {
            this.listener = listener;
        }

        @Override
        public void send(String jsonRpcPayload) {
            sent.add(jsonRpcPayload);
        }

        @Override
        public void close() {
        }

        @Override
        public TransportKind kind() {
            return TransportKind.STREAMABLE_HTTP;
        }

        @Override
        public Optional<String> sessionId() {
            return Optional.empty();
        }

        String lastSentId() {
            try {
                JsonNode node = Json.MAPPER.readTree(sent.get(sent.size() - 1));
                return node.get("id").asText();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void parseDistinguishesRequestResponseAndNotification() {
        assertTrue(JsonRpcMessage.parse("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\"}") instanceof JsonRpcRequest);
        assertTrue(JsonRpcMessage.parse("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}") instanceof JsonRpcResponse);
        assertTrue(JsonRpcMessage.parse("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}") instanceof JsonRpcNotification);
    }

    @Test
    void responseCompletesTheMatchingPendingFuture() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpSession session = new McpSession(new MessageRouter(), TrafficSink.NOOP);
        session.attachTransport(transport);

        CompletableFuture<JsonRpcResponse> future = session.request("tools/list", null);
        // send() is dispatched on McpSession's own executor — wait for it to actually reach the fake transport.
        waitUntil(() -> !transport.sent.isEmpty());

        String id = transport.lastSentId();
        transport.listener.onMessage("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"tools\":[]}}");

        JsonRpcResponse response = future.get(5, TimeUnit.SECONDS);
        assertFalse(response.isError());
        assertEquals(0, response.result().get("tools").size());

        session.close();
    }

    @Test
    void notificationIsRoutedToRegisteredHandler() throws Exception {
        FakeTransport transport = new FakeTransport();
        MessageRouter router = new MessageRouter();
        AtomicReference<JsonRpcNotification> received = new AtomicReference<>();
        router.registerNotificationHandler("notifications/progress", received::set);

        McpSession session = new McpSession(router, TrafficSink.NOOP);
        session.attachTransport(transport);

        transport.listener.onMessage("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"x\",\"progress\":1}}");

        waitUntil(() -> received.get() != null);
        assertEquals("notifications/progress", received.get().method());

        session.close();
    }

    @Test
    void unknownServerInitiatedRequestGetsMethodNotFoundReply() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpSession session = new McpSession(new MessageRouter(), TrafficSink.NOOP);
        session.attachTransport(transport);

        transport.listener.onMessage("{\"jsonrpc\":\"2.0\",\"id\":\"srv-1\",\"method\":\"sampling/createMessage\",\"params\":{}}");

        waitUntil(() -> transport.sent.stream().anyMatch(s -> s.contains("\"error\"")));
        String replyJson = transport.sent.stream().filter(s -> s.contains("\"error\"")).findFirst().orElseThrow();
        JsonNode reply = Json.MAPPER.readTree(replyJson);
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, reply.get("error").get("code").asInt());

        session.close();
    }

    @Test
    void pingIsAnsweredAutomatically() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpSession session = new McpSession(new MessageRouter(), TrafficSink.NOOP);
        session.attachTransport(transport);

        transport.listener.onMessage("{\"jsonrpc\":\"2.0\",\"id\":\"ping-1\",\"method\":\"ping\"}");

        waitUntil(() -> transport.sent.stream().anyMatch(s -> s.contains("\"result\"")));

        session.close();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for condition");
            }
            Thread.sleep(10);
        }
    }
}
