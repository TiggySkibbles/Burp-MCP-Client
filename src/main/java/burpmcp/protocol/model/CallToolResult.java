package burpmcp.protocol.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public record CallToolResult(List<ContentBlock> content, JsonNode structuredContent, boolean isError) {

    /**
     * Manually parsed (rather than a plain Jackson {@code readValue}) because {@code content}
     * is a discriminated union by {@code type} and we want to keep the full raw node for any
     * block type the MVP doesn't render specially.
     */
    public static CallToolResult fromJson(JsonNode result) {
        List<ContentBlock> blocks = new ArrayList<>();
        JsonNode contentNode = result.get("content");
        if (contentNode != null && contentNode.isArray()) {
            for (JsonNode block : contentNode) {
                String type = block.path("type").asText(null);
                String text = block.has("text") ? block.get("text").asText() : null;
                blocks.add(new ContentBlock(type, text, block));
            }
        }
        JsonNode structuredContent = result.get("structuredContent");
        boolean isError = result.path("isError").asBoolean(false);
        return new CallToolResult(blocks, structuredContent, isError);
    }
}
