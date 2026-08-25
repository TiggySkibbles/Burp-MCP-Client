package burpmcp.transport;

public interface TransportListener {
    /** One complete JSON-RPC message (request, response, or notification), as raw JSON text. */
    void onMessage(String rawJson);

    /** Raw HTTP-level detail for the traffic log, independent of JSON-RPC parsing. */
    void onRawHttpExchange(HttpExchangeLog log);

    void onError(Throwable t);

    void onClosed(String reason);
}
