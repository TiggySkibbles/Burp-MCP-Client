package burpmcp.transport;

import java.util.Optional;

/** Supplies the current bearer token (if any) for outgoing MCP requests, without transport needing to know how it was obtained. */
@FunctionalInterface
public interface TokenSupplier {
    Optional<String> bearerToken();

    TokenSupplier NONE = Optional::empty;
}
