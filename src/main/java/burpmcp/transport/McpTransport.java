package burpmcp.transport;

import java.util.Optional;

/**
 * Moves opaque JSON-RPC text in and out of an MCP server over HTTP. Knows nothing about
 * JSON-RPC semantics (correlation, method dispatch) — that's {@code protocol.McpSession}'s job.
 */
public interface McpTransport {

    /** Establishes the connection. Blocking — must be called off the Swing EDT. */
    void connect(TransportListener listener) throws TransportException;

    /**
     * Sends one JSON-RPC message. Blocking for the duration of the HTTP exchange — for a
     * streaming response this call reads the whole SSE stream before returning, pushing
     * intermediate messages to the listener as they arrive. Must be called off the EDT.
     */
    void send(String jsonRpcPayload) throws TransportException;

    /** Best-effort session teardown (DELETE for Streamable HTTP; closes the SSE loop for legacy). */
    void close();

    TransportKind kind();

    Optional<String> sessionId();

    /** Called once after a successful initialize exchange reveals the negotiated MCP-Protocol-Version. No-op for legacy. */
    default void setProtocolVersion(String version) {
    }
}
