package burpmcp.protocol.model;

public record InitializeResult(
        String protocolVersion,
        ServerCapabilities capabilities,
        ServerInfo serverInfo,
        String instructions
) {
}
