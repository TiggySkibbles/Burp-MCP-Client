package burpmcp.persistence;

import java.util.List;
import java.util.UUID;

public record ServerProfile(
        String id,
        String name,
        String serverUrl,
        TransportMode transportMode,
        List<HeaderEntry> customHeaders,
        String oauthClientId,
        String oauthClientSecret,
        boolean bypassBurpProxy,
        String manualListenerHost,
        Integer manualListenerPort,
        long createdAt,
        Long lastConnectedAt
) {
    public static ServerProfile newProfile(String name, String serverUrl) {
        return new ServerProfile(
                UUID.randomUUID().toString(),
                name,
                serverUrl,
                TransportMode.AUTO,
                List.of(),
                null,
                null,
                false,
                null,
                null,
                System.currentTimeMillis(),
                null
        );
    }

    public ServerProfile withLastConnectedNow() {
        return new ServerProfile(id, name, serverUrl, transportMode, customHeaders, oauthClientId, oauthClientSecret,
                bypassBurpProxy, manualListenerHost, manualListenerPort, createdAt, System.currentTimeMillis());
    }
}
