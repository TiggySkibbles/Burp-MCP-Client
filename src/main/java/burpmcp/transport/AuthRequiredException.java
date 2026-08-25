package burpmcp.transport;

/**
 * Thrown when a request comes back {@code 401} (or {@code 403 insufficient_scope}, mid-session).
 * Transport only knows that authorization is required and, if present, where the RFC 9728
 * protected-resource metadata / WWW-Authenticate challenge points — it has no OAuth logic itself.
 */
public class AuthRequiredException extends TransportException {

    private final String wwwAuthenticateHeader;
    private final boolean insufficientScope;

    public AuthRequiredException(String wwwAuthenticateHeader, boolean insufficientScope) {
        super(insufficientScope ? "Insufficient scope (403)" : "Authorization required (401)");
        this.wwwAuthenticateHeader = wwwAuthenticateHeader;
        this.insufficientScope = insufficientScope;
    }

    public String wwwAuthenticateHeader() {
        return wwwAuthenticateHeader;
    }

    public boolean isInsufficientScope() {
        return insufficientScope;
    }
}
