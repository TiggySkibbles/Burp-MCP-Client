package burpmcp.protocol;

import burpmcp.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record JsonRpcRequest(JsonNode id, String method, JsonNode params) implements JsonRpcMessage {

    public String toJson() {
        ObjectNode node = JsonRpcMessage.baseEnvelope();
        node.set("id", id);
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        try {
            return Json.MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON-RPC request", e);
        }
    }
}
