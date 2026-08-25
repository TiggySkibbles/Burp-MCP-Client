package burpmcp.protocol;

import burpmcp.util.Log;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of method-name handlers for server-initiated traffic (notifications, and requests the
 * server sends to the client). Follow-on phases (Resources, Prompts, Sampling, ...) add handlers
 * here without touching {@link McpSession} or the transport layer.
 */
public final class MessageRouter {

    @FunctionalInterface
    public interface NotificationHandler {
        void handle(JsonRpcNotification notification);
    }

    @FunctionalInterface
    public interface ServerRequestHandler {
        /** Return the JSON-RPC {@code result} payload, or throw to send back an error response. */
        JsonNode handle(JsonRpcRequest request) throws Exception;
    }

    private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerRequestHandler> requestHandlers = new ConcurrentHashMap<>();

    public void registerNotificationHandler(String method, NotificationHandler handler) {
        notificationHandlers.put(method, handler);
    }

    public void registerRequestHandler(String method, ServerRequestHandler handler) {
        requestHandlers.put(method, handler);
    }

    public void dispatchNotification(JsonRpcNotification notification) {
        NotificationHandler handler = notificationHandlers.get(notification.method());
        if (handler == null) {
            return;
        }
        try {
            handler.handle(notification);
        } catch (Exception e) {
            Log.error("Notification handler for '" + notification.method() + "' threw", e);
        }
    }

    /** Always returns a response to send back — "method not found" if nothing is registered. */
    public JsonRpcResponse dispatchRequest(JsonRpcRequest request) {
        ServerRequestHandler handler = requestHandlers.get(request.method());
        if (handler == null) {
            return new JsonRpcResponse(request.id(), null,
                    JsonRpcError.of(JsonRpcError.METHOD_NOT_FOUND, "Method not found: " + request.method()));
        }
        try {
            JsonNode result = handler.handle(request);
            return new JsonRpcResponse(request.id(), result, null);
        } catch (Exception e) {
            return new JsonRpcResponse(request.id(), null,
                    JsonRpcError.of(JsonRpcError.INTERNAL_ERROR, String.valueOf(e.getMessage())));
        }
    }
}
