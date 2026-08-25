package burpmcp.transport;

/**
 * How MCP/OAuth HTTP traffic should be routed. Default is through Burp's own Proxy listener
 * (§5 of the plan) so it inherits Burp's upstream proxy config and lands in Proxy History.
 */
public record NetworkSettings(boolean bypassBurpProxy, String manualListenerHost, Integer manualListenerPort) {

    public static NetworkSettings defaultSettings() {
        return new NetworkSettings(false, null, null);
    }

    public boolean hasManualListenerOverride() {
        return manualListenerHost != null && !manualListenerHost.isBlank() && manualListenerPort != null;
    }
}
