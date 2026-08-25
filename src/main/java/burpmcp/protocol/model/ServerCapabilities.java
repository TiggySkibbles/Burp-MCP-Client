package burpmcp.protocol.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Only the fields the MVP cares about (whether tools/list_changed is supported) are typed;
 * everything else (resources, prompts, sampling, logging, ...) is kept raw for AuthInfoPanel /
 * traffic-log display and for follow-on phases to type properly when they're built.
 */
public record ServerCapabilities(
        JsonNode tools,
        JsonNode resources,
        JsonNode prompts,
        JsonNode logging,
        JsonNode completions,
        JsonNode experimental
) {
    public boolean supportsTools() {
        return tools != null;
    }

    public boolean toolsListChanged() {
        return tools != null && tools.path("listChanged").asBoolean(false);
    }
}
