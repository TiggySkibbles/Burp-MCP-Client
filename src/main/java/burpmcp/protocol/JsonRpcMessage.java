package burpmcp.protocol;

import burpmcp.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Common marker for the three JSON-RPC 2.0 message shapes MCP uses on the wire.
 * Batching was removed in the 2025-06-18 revision, so each raw payload is exactly one message.
 */
public sealed interface JsonRpcMessage permits JsonRpcRequest, JsonRpcResponse, JsonRpcNotification {

    /** Parses one raw JSON-RPC object into the appropriate record. Throws on malformed input. */
    static JsonRpcMessage parse(String rawJson) {
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed JSON-RPC payload: " + e.getMessage(), e);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("JSON-RPC payload must be a JSON object");
        }

        JsonNode idNode = node.get("id");
        JsonNode methodNode = node.get("method");

        if (methodNode != null) {
            String method = methodNode.asText();
            JsonNode params = node.get("params");
            if (idNode != null && !idNode.isNull()) {
                return new JsonRpcRequest(idNode, method, params);
            }
            return new JsonRpcNotification(method, params);
        }

        if (idNode != null) {
            JsonNode resultNode = node.get("result");
            JsonNode errorNode = node.get("error");
            JsonRpcError error = null;
            if (errorNode != null && errorNode.isObject()) {
                int code = errorNode.path("code").asInt();
                String message = errorNode.path("message").asText();
                JsonNode data = errorNode.get("data");
                error = new JsonRpcError(code, message, data);
            }
            return new JsonRpcResponse(idNode, resultNode, error);
        }

        throw new IllegalArgumentException("JSON-RPC payload is neither a request, response, nor notification");
    }

    static ObjectNode baseEnvelope() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        return node;
    }
}
