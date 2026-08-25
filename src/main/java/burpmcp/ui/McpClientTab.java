package burpmcp.ui;

import burp.api.montoya.MontoyaApi;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;

/** Top-level tab: a saved-servers list + connection/auth config on the left, Tools in the middle, the raw traffic log docked on the right. */
public final class McpClientTab extends JPanel {

    private final UiController controller;

    public McpClientTab(MontoyaApi api) {
        super(new BorderLayout());

        controller = new UiController(api);

        ConnectionPanel connectionPanel = new ConnectionPanel(controller);
        ProfileListPanel profileListPanel = new ProfileListPanel(controller);
        AuthInfoPanel authInfoPanel = new AuthInfoPanel(controller::onSignInClicked, controller::onClearOAuthState);
        ToolsPanel toolsPanel = new ToolsPanel(controller);
        TrafficLogPanel trafficLogPanel = new TrafficLogPanel();

        controller.attachPanels(connectionPanel, profileListPanel, authInfoPanel, toolsPanel, trafficLogPanel);

        JPanel configStack = new JPanel();
        configStack.setLayout(new BoxLayout(configStack, BoxLayout.Y_AXIS));
        configStack.add(connectionPanel);
        configStack.add(authInfoPanel);

        JPanel topConfig = new JPanel(new BorderLayout());
        topConfig.add(profileListPanel, BorderLayout.WEST);
        topConfig.add(configStack, BorderLayout.CENTER);

        JSplitPane workingSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(topConfig), toolsPanel);
        workingSplit.setResizeWeight(0.35);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workingSplit, trafficLogPanel);
        mainSplit.setResizeWeight(0.6);

        add(mainSplit, BorderLayout.CENTER);
    }

    public void shutdown() {
        controller.shutdown();
    }
}
