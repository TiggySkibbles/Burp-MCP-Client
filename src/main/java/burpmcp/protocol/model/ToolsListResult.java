package burpmcp.protocol.model;

import java.util.List;

public record ToolsListResult(List<Tool> tools, String nextCursor) {
}
