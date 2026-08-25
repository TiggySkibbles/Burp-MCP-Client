package burpmcp.persistence;

/** Cached RFC 9728 / RFC 8414 discovery results plus the registered DCR client, keyed by canonical resource URI. */
public record CachedDiscovery(
        String protectedResourceMetadataRawJson,
        String authServerMetadataRawJson,
        String registeredClientId,
        String registeredClientSecret,
        long discoveredAtEpochMillis
) {
    private static final long DISCOVERY_TTL_MILLIS = 24L * 60 * 60 * 1000;

    public boolean isStale() {
        return System.currentTimeMillis() - discoveredAtEpochMillis > DISCOVERY_TTL_MILLIS;
    }
}
