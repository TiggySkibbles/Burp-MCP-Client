package burpmcp.protocol.model;

import com.fasterxml.jackson.databind.JsonNode;

public record Tool(
        String name,
        String title,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        JsonNode annotations
) {
    public String displayName() {
        return (title != null && !title.isBlank()) ? title : name;
    }
}
