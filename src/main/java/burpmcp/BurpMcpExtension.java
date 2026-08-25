package burpmcp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burpmcp.ui.McpClientTab;
import burpmcp.util.Log;

public final class BurpMcpExtension implements BurpExtension {

    private McpClientTab tab;

    @Override
    public void initialize(MontoyaApi api) {
        Log.init(api.logging());
        api.extension().setName("MCP Client");

        tab = new McpClientTab(api);
        api.userInterface().registerSuiteTab("MCP Client", tab);

        api.extension().registerUnloadingHandler(() -> {
            if (tab != null) {
                tab.shutdown();
            }
        });

        Log.info("Burp MCP Client extension loaded.");
    }
}
