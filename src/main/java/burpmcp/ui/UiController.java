package burpmcp.ui;

import burp.api.montoya.MontoyaApi;
import burpmcp.oauth.OAuthManager;
import burpmcp.persistence.PersistenceService;
import burpmcp.persistence.ServerProfile;
import burpmcp.persistence.TransportMode;
import burpmcp.protocol.McpProtocolException;
import burpmcp.protocol.McpSession;
import burpmcp.protocol.MessageRouter;
import burpmcp.protocol.TrafficDirection;
import burpmcp.protocol.TrafficSink;
import burpmcp.protocol.model.CallToolResult;
import burpmcp.protocol.model.ClientInfo;
import burpmcp.protocol.model.InitializeResult;
import burpmcp.protocol.model.Tool;
import burpmcp.transport.AuthRequiredException;
import burpmcp.transport.BurpProxyHttpClientFactory;
import burpmcp.transport.HttpExchangeLog;
import burpmcp.transport.LegacySseTransport;
import burpmcp.transport.NetworkSettings;
import burpmcp.transport.StreamableHttpTransport;
import burpmcp.transport.TokenSupplier;
import burpmcp.transport.TransportDetector;
import burpmcp.util.Log;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.JOptionPane;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Mediates every UI action: EDT listeners call in here, work is submitted to a background
 * executor, and results are marshalled back to Swing via {@link UiEventBridge}. Owns the
 * lifecycle of the active {@link McpSession}/{@link OAuthManager} for the current connection.
 */
public final class UiController implements ConnectionPanel.Listener, ProfileListPanel.Listener, ToolsPanel.Listener {

    private final MontoyaApi api;
    private final PersistenceService persistence;
    private final BurpProxyHttpClientFactory httpClientFactory;
    private final ExecutorService bgExecutor;

    private ConnectionPanel connectionPanel;
    private ProfileListPanel profileListPanel;
    private AuthInfoPanel authInfoPanel;
    private ToolsPanel toolsPanel;
    private TrafficLogPanel trafficLogPanel;

    private volatile McpSession activeSession;
    private volatile OAuthManager activeOAuthManager;
    private volatile URI activeServerUri;
    private volatile String activeCallId;
    private volatile String lastWwwAuthHeader;
    private volatile PendingConnect pendingConnect;

    private record PendingConnect(ServerProfile profile, URI serverUri, Map<String, String> headers,
                                   HttpClient httpClient, OAuthManager oauthManager, MessageRouter router,
                                   McpSession session, ClientInfo clientInfo) {
    }

    public UiController(MontoyaApi api) {
        this.api = api;
        this.persistence = new PersistenceService(api);
        this.httpClientFactory = new BurpProxyHttpClientFactory(api);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "mcp-ui-controller-bg");
            t.setDaemon(true);
            return t;
        };
        this.bgExecutor = Executors.newCachedThreadPool(tf);
    }

    public void attachPanels(ConnectionPanel connectionPanel, ProfileListPanel profileListPanel,
                              AuthInfoPanel authInfoPanel, ToolsPanel toolsPanel, TrafficLogPanel trafficLogPanel) {
        this.connectionPanel = connectionPanel;
        this.profileListPanel = profileListPanel;
        this.authInfoPanel = authInfoPanel;
        this.toolsPanel = toolsPanel;
        this.trafficLogPanel = trafficLogPanel;
        profileListPanel.setProfiles(persistence.loadProfiles());
    }

    public void shutdown() {
        McpSession s = activeSession;
        if (s != null) {
            s.close();
        }
        bgExecutor.shutdownNow();
    }

    // ---- ConnectionPanel.Listener -----------------------------------------------------------

    @Override
    public void onConnect() {
        ServerProfile profile = connectionPanel.buildProfileFromFields();
        if (profile.serverUrl() == null || profile.serverUrl().isBlank()) {
            JOptionPane.showMessageDialog(connectionPanel, "Enter a server URL first.", "Missing URL", JOptionPane.WARNING_MESSAGE);
            return;
        }
        connectionPanel.setStatus("Connecting...");
        authInfoPanel.reset();
        bgExecutor.submit(() -> doConnect(profile));
    }

    private void doConnect(ServerProfile profile) {
        try {
            URI serverUri = URI.create(profile.serverUrl());
            NetworkSettings networkSettings = new NetworkSettings(profile.bypassBurpProxy(), profile.manualListenerHost(), profile.manualListenerPort());
            HttpClient httpClient = httpClientFactory.buildClient(networkSettings);

            OAuthManager oauthManager = new OAuthManager(httpClient, persistence, this::onOAuthHttpExchange);
            Map<String, String> headers = new LinkedHashMap<>();
            profile.customHeaders().forEach(h -> headers.put(h.name(), h.value()));

            MessageRouter router = new MessageRouter();
            McpSession session = new McpSession(router, sessionTrafficSink());
            router.registerNotificationHandler("notifications/tools/list_changed", n -> refreshTools(session));

            ClientInfo clientInfo = new ClientInfo("burp-mcp-client", "Burp MCP Client", "0.1.0");

            InitializeResult result = attemptInitialize(session, profile.transportMode(), serverUri, headers,
                    () -> oauthManager.currentBearerToken(serverUri), httpClient, clientInfo);

            activateConnection(profile, serverUri, oauthManager, session, result);
        } catch (AuthRequiredException authEx) {
            handleConnectAuthRequired(authEx, profile);
        } catch (Exception e) {
            Log.error("Connect failed", e);
            UiEventBridge.post(() -> {
                connectionPanel.setStatus("Connect failed: " + e.getMessage());
                connectionPanel.setConnected(false);
            });
        }
    }

    private InitializeResult attemptInitialize(McpSession session, TransportMode mode, URI serverUri,
                                                Map<String, String> headers, TokenSupplier tokens,
                                                HttpClient httpClient, ClientInfo clientInfo) throws Exception {
        if (mode == TransportMode.STREAMABLE_HTTP) {
            session.attachTransport(new StreamableHttpTransport(serverUri, headers, tokens, httpClient));
            return session.initializeBlocking(clientInfo);
        }
        if (mode == TransportMode.LEGACY_SSE) {
            session.attachTransport(new LegacySseTransport(serverUri, headers, tokens, httpClient));
            return session.initializeBlocking(clientInfo);
        }
        return TransportDetector.connectAndInitialize(session, serverUri, headers, tokens, httpClient, clientInfo);
    }

    private void handleConnectAuthRequired(AuthRequiredException authEx, ServerProfile profile) {
        try {
            URI serverUri = URI.create(profile.serverUrl());
            NetworkSettings networkSettings = new NetworkSettings(profile.bypassBurpProxy(), profile.manualListenerHost(), profile.manualListenerPort());
            HttpClient httpClient = httpClientFactory.buildClient(networkSettings);
            OAuthManager oauthManager = new OAuthManager(httpClient, persistence, this::onOAuthHttpExchange);
            Map<String, String> headers = new LinkedHashMap<>();
            profile.customHeaders().forEach(h -> headers.put(h.name(), h.value()));
            MessageRouter router = new MessageRouter();
            McpSession session = new McpSession(router, sessionTrafficSink());
            router.registerNotificationHandler("notifications/tools/list_changed", n -> refreshTools(session));
            ClientInfo clientInfo = new ClientInfo("burp-mcp-client", "Burp MCP Client", "0.1.0");

            this.lastWwwAuthHeader = authEx.wwwAuthenticateHeader();
            this.pendingConnect = new PendingConnect(profile, serverUri, headers, httpClient, oauthManager, router, session, clientInfo);

            UiEventBridge.post(() -> {
                connectionPanel.setStatus("Authorization required");
                authInfoPanel.showSignInRequired();
            });
        } catch (Exception e) {
            Log.error("Failed to prepare for authorization", e);
            UiEventBridge.post(() -> connectionPanel.setStatus("Connect failed: " + e.getMessage()));
        }
    }

    public void onSignInClicked() {
        PendingConnect pc = pendingConnect;
        if (pc != null) {
            bgExecutor.submit(() -> {
                try {
                    pc.oauthManager().startAuthorizationFlow(pc.serverUri(), lastWwwAuthHeader,
                            pc.profile().oauthClientId(), pc.profile().oauthClientSecret()).get();
                    InitializeResult result = attemptInitialize(pc.session(), pc.profile().transportMode(), pc.serverUri(),
                            pc.headers(), () -> pc.oauthManager().currentBearerToken(pc.serverUri()), pc.httpClient(), pc.clientInfo());
                    pendingConnect = null;
                    activateConnection(pc.profile(), pc.serverUri(), pc.oauthManager(), pc.session(), result);
                } catch (Exception e) {
                    Log.error("Sign-in / retry failed", e);
                    UiEventBridge.post(() -> JOptionPane.showMessageDialog(connectionPanel, "Sign-in failed: " + e.getMessage(),
                            "Authorization failed", JOptionPane.ERROR_MESSAGE));
                }
            });
            return;
        }

        // Mid-session re-auth: refresh/replace the token; the active transport picks it up on the next call.
        OAuthManager mgr = activeOAuthManager;
        URI uri = activeServerUri;
        if (mgr == null || uri == null) {
            return;
        }
        bgExecutor.submit(() -> {
            try {
                mgr.startAuthorizationFlow(uri, lastWwwAuthHeader, null, null).get();
                UiEventBridge.post(() -> authInfoPanel.update(mgr.currentDiagnostics(uri)));
            } catch (Exception e) {
                Log.error("Re-authorization failed", e);
                UiEventBridge.post(() -> JOptionPane.showMessageDialog(connectionPanel, "Sign-in failed: " + e.getMessage(),
                        "Authorization failed", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    public void onClearOAuthState() {
        OAuthManager mgr = activeOAuthManager;
        URI uri = activeServerUri;
        if (mgr == null || uri == null) {
            return;
        }
        mgr.clearState(uri);
        UiEventBridge.post(() -> authInfoPanel.update(mgr.currentDiagnostics(uri)));
    }

    private void activateConnection(ServerProfile profile, URI serverUri, OAuthManager oauthManager, McpSession session, InitializeResult result) {
        McpSession previous = this.activeSession;
        if (previous != null && previous != session) {
            previous.close();
        }
        this.activeSession = session;
        this.activeOAuthManager = oauthManager;
        this.activeServerUri = serverUri;

        UiEventBridge.post(() -> {
            connectionPanel.setStatus("Connected — " + (result.serverInfo() != null ? result.serverInfo().name() : "unknown server")
                    + " (" + session.transport().kind() + ")");
            connectionPanel.setConnected(true);
            authInfoPanel.update(oauthManager.currentDiagnostics(serverUri));
        });

        refreshTools(session);
    }

    private void refreshTools(McpSession session) {
        session.listAllTools().whenComplete((tools, throwable) -> {
            if (throwable != null) {
                Log.error("Failed to list tools", throwable);
                return;
            }
            UiEventBridge.post(() -> toolsPanel.setTools(tools));
        });
    }

    @Override
    public void onDisconnect() {
        McpSession session = activeSession;
        activeSession = null;
        activeOAuthManager = null;
        activeServerUri = null;
        pendingConnect = null;
        if (session != null) {
            bgExecutor.submit(session::close);
        }
        UiEventBridge.post(() -> {
            connectionPanel.setStatus("Not connected");
            connectionPanel.setConnected(false);
            authInfoPanel.reset();
            toolsPanel.clear();
        });
    }

    // ---- ProfileListPanel.Listener ----------------------------------------------------------

    @Override
    public void onProfileSelected(ServerProfile profile) {
        connectionPanel.populateFromProfile(profile);
    }

    @Override
    public void onNewProfile() {
        connectionPanel.newProfile();
    }

    @Override
    public void onSaveProfile() {
        ServerProfile profile = connectionPanel.buildProfileFromFields();
        List<ServerProfile> profiles = new ArrayList<>(persistence.loadProfiles());
        profiles.removeIf(p -> p.id().equals(profile.id()));
        profiles.add(profile);
        persistence.saveProfiles(profiles);
        profileListPanel.setProfiles(profiles);
    }

    @Override
    public void onDeleteProfile(ServerProfile profile) {
        List<ServerProfile> profiles = new ArrayList<>(persistence.loadProfiles());
        profiles.removeIf(p -> p.id().equals(profile.id()));
        persistence.saveProfiles(profiles);
        profileListPanel.setProfiles(profiles);
    }

    // ---- ToolsPanel.Listener ------------------------------------------------------------------

    @Override
    public void onInvoke(Tool tool, JsonNode arguments) {
        McpSession session = activeSession;
        if (session == null) {
            return;
        }
        McpSession.TrackedRequest<CallToolResult> tracked = session.callTool(tool.name(), arguments,
                progressParams -> UiEventBridge.post(() -> toolsPanel.showProgress(progressParams)));
        this.activeCallId = tracked.id();
        tracked.future().whenComplete((result, throwable) -> {
            if (tracked.id().equals(this.activeCallId)) {
                this.activeCallId = null;
            }
            if (throwable != null) {
                McpProtocolException protoErr = McpSession.asProtocolException(throwable);
                if (protoErr != null) {
                    UiEventBridge.post(() -> toolsPanel.showProtocolError(protoErr.getMessage()));
                } else if (unwrapAuthRequired(throwable) != null) {
                    UiEventBridge.post(() -> {
                        toolsPanel.showTransportError("Authorization expired or insufficient — sign in again from the Authorization panel, then retry.");
                        authInfoPanel.showSignInRequired();
                    });
                    this.lastWwwAuthHeader = unwrapAuthRequired(throwable).wwwAuthenticateHeader();
                } else {
                    UiEventBridge.post(() -> toolsPanel.showTransportError(String.valueOf(throwable.getMessage())));
                }
                return;
            }
            UiEventBridge.post(() -> toolsPanel.showResult(result));
        });
    }

    @Override
    public void onCancel() {
        McpSession session = activeSession;
        String id = activeCallId;
        if (session != null && id != null) {
            session.cancel(id, "user requested");
        }
    }

    private static AuthRequiredException unwrapAuthRequired(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof AuthRequiredException are) {
                return are;
            }
            cause = cause.getCause();
        }
        return null;
    }

    // ---- Traffic log wiring --------------------------------------------------------------------

    private TrafficSink sessionTrafficSink() {
        return new TrafficSink() {
            @Override
            public void onJsonRpcMessage(TrafficDirection direction, String tag, String rawJson) {
                trafficLogPanel.logJsonRpc(direction, tag, rawJson);
            }

            @Override
            public void onHttpExchange(HttpExchangeLog log) {
                trafficLogPanel.logHttpExchange(log);
            }
        };
    }

    private void onOAuthHttpExchange(HttpExchangeLog log) {
        trafficLogPanel.logHttpExchange(log);
    }
}
