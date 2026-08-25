package burpmcp.transport;

import java.util.List;
import java.util.Map;

/**
 * Raw HTTP-level detail for one exchange, pushed to the traffic log independent of JSON-RPC
 * parsing. {@code tag} groups entries in the UI (e.g. "MCP" vs "OAuth").
 */
public record HttpExchangeLog(
        String tag,
        String method,
        String url,
        Map<String, List<String>> requestHeaders,
        String requestBody,
        int statusCode,
        Map<String, List<String>> responseHeaders,
        String responseContentType
) {
}
