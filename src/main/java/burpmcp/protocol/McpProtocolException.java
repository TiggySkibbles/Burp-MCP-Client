package burpmcp.protocol;

/**
 * A genuine JSON-RPC protocol-level failure (e.g. {@code -32602 Invalid Params}) — distinct from
 * a tool reporting {@code isError: true} in an otherwise-successful {@link burpmcp.protocol.model.CallToolResult},
 * which is not an exception at all. UI code must render these two cases differently.
 */
public class McpProtocolException extends Exception {
    private final JsonRpcError error;

    public McpProtocolException(JsonRpcError error) {
        super("MCP protocol error " + error.code() + ": " + error.message());
        this.error = error;
    }

    public JsonRpcError error() {
        return error;
    }
}
