package burpmcp.transport;

import burpmcp.protocol.McpProtocolException;
import burpmcp.protocol.McpSession;
import burpmcp.protocol.model.ClientInfo;
import burpmcp.protocol.model.InitializeResult;
import burpmcp.util.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

/**
 * Implements the MCP spec's recommended detection algorithm: attempt a Streamable-HTTP-style
 * POST {@code initialize} first; if the server responds 404/405/406 (via {@link TransportUnsupportedException}),
 * fall back to the legacy two-endpoint GET/SSE transport. This performs the real {@code initialize}
 * call — not a throwaway probe — so the common (modern server) case only ever calls initialize once.
 */
public final class TransportDetector {

    private TransportDetector() {
    }

    public static InitializeResult connectAndInitialize(
            McpSession session,
            URI serverUrl,
            Map<String, String> headers,
            TokenSupplier tokens,
            HttpClient httpClient,
            ClientInfo clientInfo
    ) throws TransportException, McpProtocolException, InterruptedException {

        StreamableHttpTransport streamable = new StreamableHttpTransport(serverUrl, headers, tokens, httpClient);
        try {
            session.attachTransport(streamable);
            return session.initializeBlocking(clientInfo);
        } catch (TransportUnsupportedException e) {
            Log.info("Server did not accept a Streamable HTTP initialize (" + e.getMessage()
                    + ") — falling back to legacy HTTP+SSE transport");
            streamable.close();

            LegacySseTransport legacy = new LegacySseTransport(serverUrl, headers, tokens, httpClient);
            session.attachTransport(legacy);
            return session.initializeBlocking(clientInfo);
        }
    }
}
