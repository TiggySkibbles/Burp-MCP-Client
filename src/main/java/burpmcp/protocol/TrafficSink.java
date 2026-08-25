package burpmcp.protocol;

import burpmcp.transport.HttpExchangeLog;

/**
 * Receives every JSON-RPC message and raw HTTP exchange as it happens, independent of
 * {@link MessageRouter} dispatch — so the traffic log stays complete even for methods/notifications
 * no handler is registered for yet (e.g. a follow-on-phase capability the server uses unsolicited).
 */
public interface TrafficSink {
    void onJsonRpcMessage(TrafficDirection direction, String tag, String rawJson);

    void onHttpExchange(HttpExchangeLog log);

    TrafficSink NOOP = new TrafficSink() {
        @Override
        public void onJsonRpcMessage(TrafficDirection direction, String tag, String rawJson) {
        }

        @Override
        public void onHttpExchange(HttpExchangeLog log) {
        }
    };
}
