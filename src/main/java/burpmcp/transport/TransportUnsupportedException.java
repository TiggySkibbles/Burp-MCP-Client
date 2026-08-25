package burpmcp.transport;

/**
 * Signals "this server doesn't speak Streamable HTTP" (404/405/406 on the initialize POST),
 * distinct from a genuine connection/auth failure. {@link TransportDetector} catches this
 * specifically to fall back to {@link LegacySseTransport}.
 */
public class TransportUnsupportedException extends TransportException {
    public TransportUnsupportedException(String message) {
        super(message);
    }
}
