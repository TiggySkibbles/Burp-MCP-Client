package burpmcp.protocol.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single block of a {@link CallToolResult}'s {@code content} array. The MVP renders
 * {@code type: "text"} fully; other types (image, audio, resource, resource_link) are kept as
 * raw JSON via {@code data} and shown as "not rendered — see raw JSON" in ToolsPanel.
 */
public record ContentBlock(String type, String text, JsonNode data) {
}
