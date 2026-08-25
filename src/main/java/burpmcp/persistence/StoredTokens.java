package burpmcp.persistence;

public record StoredTokens(
        String accessToken,
        String refreshToken,
        long expiresAtEpochMillis,
        String scope,
        String tokenType
) {
    private static final long CLOCK_SKEW_MARGIN_MILLIS = 60_000L;

    public boolean isExpired() {
        return System.currentTimeMillis() >= (expiresAtEpochMillis - CLOCK_SKEW_MARGIN_MILLIS);
    }
}
