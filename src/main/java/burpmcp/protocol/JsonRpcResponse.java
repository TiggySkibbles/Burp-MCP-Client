package burpmcp.protocol;

import burpmcp.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record JsonRpcResponse(JsonNode id, JsonNode result, JsonRpcError error) implements JsonRpcMessage {

    public boolean isError() {
        return error != null;
    }

    /** Used when replying to a server-initiated request (e.g. a future sampling/elicitation call). */
    public String toJson() {
        ObjectNode node = JsonRpcMessage.baseEnvelope();
        node.set("id", id);
        if (error != null) {
            ObjectNode errorNode = Json.MAPPER.createObjectNode();
            errorNode.put("code", error.code());
            errorNode.put("message", error.message());
            if (error.data() != null) {
                errorNode.set("data", error.data());
            }
            node.set("error", errorNode);
        } else {
            node.set("result", result == null ? Json.MAPPER.createObjectNode() : result);
        }
        try {
            return Json.MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON-RPC response", e);
        }
    }
}
