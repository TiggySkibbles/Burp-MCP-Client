package burpmcp.persistence;

/** User's transport preference for a saved profile — distinct from the actually-detected {@link burpmcp.transport.TransportKind}. */
public enum TransportMode {
    AUTO,
    STREAMABLE_HTTP,
    LEGACY_SSE
}
