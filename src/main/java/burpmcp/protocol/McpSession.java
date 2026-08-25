package burpmcp.protocol;

import burpmcp.protocol.model.CallToolResult;
import burpmcp.protocol.model.ClientInfo;
import burpmcp.protocol.model.InitializeResult;
import burpmcp.protocol.model.Tool;
import burpmcp.protocol.model.ToolsListResult;
import burpmcp.transport.HttpExchangeLog;
import burpmcp.transport.McpTransport;
import burpmcp.transport.TransportException;
import burpmcp.transport.TransportListener;
import burpmcp.util.Json;
import burpmcp.util.Log;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns request-id correlation and JSON-RPC dispatch for one connection. Transport-agnostic:
 * works identically whichever {@link McpTransport} is attached. Every request/response/notification
 * also flows to the {@link TrafficSink}, independent of {@link MessageRouter} dispatch.
 */
public final class McpSession implements TransportListener {

    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final MessageRouter router;
    private final TrafficSink trafficSink;
    private final ExecutorService executor;
    private final AtomicLong idCounter = new AtomicLong(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<JsonNode>> progressListeners = new ConcurrentHashMap<>();

    private volatile McpTransport transport;

    public McpSession(MessageRouter router, TrafficSink trafficSink) {
        this.router = router;
        this.trafficSink = trafficSink;
        ThreadFactory tf = runnable -> {
            Thread t = new Thread(runnable, "mcp-session-worker-" + System.identityHashCode(this));
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newCachedThreadPool(tf);

        router.registerRequestHandler("ping", request -> Json.MAPPER.createObjectNode());
    }

    /** Replaces the active transport and wires it up to this session. Blocking — call off the EDT. */
    public void attachTransport(McpTransport newTransport) throws TransportException {
        this.transport = newTransport;
        newTransport.connect(this);
    }

    public McpTransport transport() {
        return transport;
    }

    // ---- Public request/notify API -------------------------------------------------------

    /** A request together with the JSON-RPC id it was sent under, so a caller can later cancel it. */
    public record TrackedRequest<T>(String id, CompletableFuture<T> future) {
    }

    public CompletableFuture<JsonRpcResponse> request(String method, ObjectNode params) {
        return request(method, params, null);
    }

    public CompletableFuture<JsonRpcResponse> request(String method, ObjectNode params, Consumer<JsonNode> progressListener) {
        return requestTracked(method, params, progressListener).future();
    }

    public TrackedRequest<JsonRpcResponse> requestTracked(String method, ObjectNode params, Consumer<JsonNode> progressListener) {
        String id = "req-" + idCounter.getAndIncrement();
        JsonNode idNode = com.fasterxml.jackson.databind.node.TextNode.valueOf(id);

        ObjectNode actualParams = params != null ? params : Json.MAPPER.createObjectNode();
        String progressToken = null;
        if (progressListener != null) {
            progressToken = "pt-" + id;
            ObjectNode meta = Json.MAPPER.createObjectNode();
            meta.put("progressToken", progressToken);
            actualParams.set("_meta", meta);
            progressListeners.put(progressToken, progressListener);
        }

        JsonRpcRequest jsonRpcRequest = new JsonRpcRequest(idNode, method, actualParams);
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pending.put(id, future);

        String finalProgressToken = progressToken;
        future.whenComplete((r, t) -> {
            if (finalProgressToken != null) {
                progressListeners.remove(finalProgressToken);
            }
        });

        McpTransport t = transport;
        if (t == null) {
            pending.remove(id);
            future.completeExceptionally(new IllegalStateException("Not connected"));
            return new TrackedRequest<>(id, future);
        }

        executor.submit(() -> {
            String rawJson = jsonRpcRequest.toJson();
            trafficSink.onJsonRpcMessage(TrafficDirection.SENT, "MCP", rawJson);
            try {
                t.send(rawJson);
            } catch (Exception e) {
                pending.remove(id);
                future.completeExceptionally(e);
            }
        });

        return new TrackedRequest<>(id, future);
    }

    public void notify(String method, ObjectNode params) {
        McpTransport t = transport;
        if (t == null) {
            return;
        }
        JsonRpcNotification notification = new JsonRpcNotification(method, params);
        executor.submit(() -> {
            String rawJson = notification.toJson();
            trafficSink.onJsonRpcMessage(TrafficDirection.SENT, "MCP", rawJson);
            try {
                t.send(rawJson);
            } catch (Exception e) {
                Log.error("Failed to send notification '" + method + "'", e);
            }
        });
    }

    public void cancel(String requestId, String reason) {
        ObjectNode params = Json.MAPPER.createObjectNode();
        params.put("requestId", requestId);
        if (reason != null) {
            params.put("reason", reason);
        }
        notify("notifications/cancelled", params);
    }

    // ---- Typed MCP operations --------------------------------------------------------------

    public CompletableFuture<InitializeResult> initialize(ClientInfo clientInfo) {
        ObjectNode params = Json.MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", Json.MAPPER.createObjectNode());
        params.set("clientInfo", Json.MAPPER.valueToTree(clientInfo));

        return request("initialize", params).thenApply(response -> {
            if (response.isError()) {
                throw new McpProtocolWrapper(response.error());
            }
            InitializeResult result = Json.MAPPER.convertValue(response.result(), InitializeResult.class);
            McpTransport t = transport;
            if (t != null && result.protocolVersion() != null) {
                t.setProtocolVersion(result.protocolVersion());
            }
            notify("notifications/initialized", null);
            return result;
        });
    }

    /** Blocking convenience for {@link burpmcp.transport.TransportDetector}. Never call on the EDT. */
    public InitializeResult initializeBlocking(ClientInfo clientInfo) throws TransportException, McpProtocolException, InterruptedException {
        try {
            return initialize(clientInfo).get();
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    public CompletableFuture<ToolsListResult> listTools(String cursor) {
        ObjectNode params = Json.MAPPER.createObjectNode();
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return request("tools/list", params).thenApply(response -> {
            if (response.isError()) {
                throw new McpProtocolWrapper(response.error());
            }
            return Json.MAPPER.convertValue(response.result(), ToolsListResult.class);
        });
    }

    /** Fetches every page of tools/list, aggregating into one list (capped to avoid a pathological server looping forever). */
    public CompletableFuture<List<Tool>> listAllTools() {
        java.util.ArrayList<Tool> all = new java.util.ArrayList<>();
        return listAllToolsPage(null, all, 0);
    }

    private CompletableFuture<List<Tool>> listAllToolsPage(String cursor, java.util.List<Tool> accumulator, int pageCount) {
        if (pageCount > 200) {
            return CompletableFuture.completedFuture(accumulator);
        }
        return listTools(cursor).thenCompose(page -> {
            accumulator.addAll(page.tools());
            if (page.nextCursor() == null || page.nextCursor().isBlank()) {
                return CompletableFuture.completedFuture(accumulator);
            }
            return listAllToolsPage(page.nextCursor(), accumulator, pageCount + 1);
        });
    }

    public TrackedRequest<CallToolResult> callTool(String name, JsonNode arguments, Consumer<JsonNode> progressListener) {
        ObjectNode params = Json.MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments != null ? arguments : Json.MAPPER.createObjectNode());
        TrackedRequest<JsonRpcResponse> tracked = requestTracked("tools/call", params, progressListener);
        CompletableFuture<CallToolResult> resultFuture = tracked.future().thenApply(response -> {
            if (response.isError()) {
                throw new McpProtocolWrapper(response.error());
            }
            return CallToolResult.fromJson(response.result());
        });
        return new TrackedRequest<>(tracked.id(), resultFuture);
    }

    // ---- TransportListener (dispatch loop) -------------------------------------------------

    @Override
    public void onMessage(String rawJson) {
        trafficSink.onJsonRpcMessage(TrafficDirection.RECEIVED, "MCP", rawJson);
        JsonRpcMessage message;
        try {
            message = JsonRpcMessage.parse(rawJson);
        } catch (Exception e) {
            Log.error("Received malformed JSON-RPC message, ignoring: " + rawJson, e);
            return;
        }

        if (message instanceof JsonRpcResponse response) {
            handleResponse(response);
        } else if (message instanceof JsonRpcNotification notification) {
            handleNotification(notification);
        } else if (message instanceof JsonRpcRequest serverRequest) {
            handleServerRequest(serverRequest);
        }
    }

    private void handleResponse(JsonRpcResponse response) {
        String id = response.id() != null ? response.id().asText() : null;
        CompletableFuture<JsonRpcResponse> future = id != null ? pending.remove(id) : null;
        if (future != null) {
            future.complete(response);
        }
    }

    private void handleNotification(JsonRpcNotification notification) {
        if ("notifications/progress".equals(notification.method()) && notification.params() != null) {
            String token = notification.params().path("progressToken").asText(null);
            if (token != null) {
                Consumer<JsonNode> listener = progressListeners.get(token);
                if (listener != null) {
                    listener.accept(notification.params());
                }
            }
        }
        router.dispatchNotification(notification);
    }

    private void handleServerRequest(JsonRpcRequest serverRequest) {
        JsonRpcResponse reply = router.dispatchRequest(serverRequest);
        McpTransport t = transport;
        if (t == null) {
            return;
        }
        executor.submit(() -> {
            String rawJson = reply.toJson();
            trafficSink.onJsonRpcMessage(TrafficDirection.SENT, "MCP", rawJson);
            try {
                t.send(rawJson);
            } catch (Exception e) {
                Log.error("Failed to send reply to server-initiated request '" + serverRequest.method() + "'", e);
            }
        });
    }

    @Override
    public void onRawHttpExchange(HttpExchangeLog log) {
        trafficSink.onHttpExchange(log);
    }

    @Override
    public void onError(Throwable t) {
        Log.error("Transport error", t);
        failAllPending(t);
    }

    @Override
    public void onClosed(String reason) {
        Log.info("Transport closed: " + reason);
        failAllPending(new IllegalStateException("Connection closed: " + reason));
    }

    private void failAllPending(Throwable t) {
        pending.forEach((id, future) -> future.completeExceptionally(t));
        pending.clear();
    }

    public void close() {
        McpTransport t = transport;
        if (t != null) {
            t.close();
        }
        failAllPending(new IllegalStateException("Session closed"));
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Unwraps a {@code CompletableFuture} failure (from {@link #listTools}/{@link #callTool}/etc.,
     * typically seen via {@code CompletionException} from {@code .join()} or {@code ExecutionException}
     * from {@code .get()}) into an {@link McpProtocolException} if that's what it was, or {@code null}
     * if the failure was something else (a {@link TransportException}, generally).
     */
    public static McpProtocolException asProtocolException(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.util.concurrent.CompletionException || cause instanceof ExecutionException) {
            if (cause.getCause() == null) {
                break;
            }
            cause = cause.getCause();
        }
        if (cause instanceof McpProtocolWrapper wrapper) {
            return new McpProtocolException(wrapper.error);
        }
        return null;
    }

    private static TransportException unwrap(ExecutionException e) throws McpProtocolException {
        Throwable cause = e.getCause();
        if (cause instanceof McpProtocolWrapper wrapper) {
            throw new McpProtocolException(wrapper.error);
        }
        if (cause instanceof TransportException te) {
            return te;
        }
        return new TransportException("Unexpected failure: " + cause, cause);
    }

    /** Internal unchecked carrier so initialize()'s CompletableFuture chain can surface a JSON-RPC error without a checked-exception lambda. */
    private static final class McpProtocolWrapper extends RuntimeException {
        final JsonRpcError error;

        McpProtocolWrapper(JsonRpcError error) {
            this.error = error;
        }
    }
}
