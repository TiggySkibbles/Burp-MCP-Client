package burpmcp.oauth;

/** What {@code AuthInfoPanel} shows: discovery results, registered client, granted scopes, token status. */
public record AuthDiagnostics(
        boolean hasToken,
        boolean tokenExpired,
        String clientId,
        String scope,
        Long expiresAtEpochMillis,
        String authorizationEndpoint,
        String tokenEndpoint,
        String issuer
) {
    public static final AuthDiagnostics NONE = new AuthDiagnostics(false, false, null, null, null, null, null, null);
}
