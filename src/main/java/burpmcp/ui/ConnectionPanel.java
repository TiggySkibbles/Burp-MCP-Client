package burpmcp.ui;

import burpmcp.persistence.HeaderEntry;
import burpmcp.persistence.ServerProfile;
import burpmcp.persistence.TransportMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConnectionPanel extends JPanel {

    public interface Listener {
        void onConnect();

        void onDisconnect();
    }

    private final JTextField nameField = new JTextField("New server");
    private final JTextField urlField = new JTextField();
    private final JComboBox<TransportMode> transportModeCombo = new JComboBox<>(TransportMode.values());
    private final JTextArea headersArea = new JTextArea(3, 30);
    private final JCheckBox bypassProxyCheckbox = new JCheckBox("Connect directly (bypass Burp's Proxy listener)");
    private final JTextField manualListenerField = new JTextField();
    private final JTextField oauthClientIdField = new JTextField();
    private final JTextField oauthClientSecretField = new JTextField();
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final JLabel statusLabel = new JLabel("Not connected");

    private String currentProfileId = UUID.randomUUID().toString();
    private long createdAt = System.currentTimeMillis();

    public ConnectionPanel(Listener listener) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder("Server connection"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        row = addRow(gbc, row, "Name:", nameField);
        row = addRow(gbc, row, "Server URL:", urlField);
        row = addRow(gbc, row, "Transport:", transportModeCombo);
        row = addRow(gbc, row, "Custom headers (one \"Name: Value\" per line):", new JScrollPane(headersArea));

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        add(bypassProxyCheckbox, gbc);
        row++;

        row = addRow(gbc, row, "Manual proxy listener (host:port, optional override):", manualListenerField);
        row = addRow(gbc, row, "OAuth client id (optional, skips Dynamic Client Registration):", oauthClientIdField);
        row = addRow(gbc, row, "OAuth client secret (optional):", oauthClientSecretField);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        add(connectButton, gbc);
        gbc.gridx = 1;
        add(disconnectButton, gbc);
        row++;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        add(statusLabel, gbc);

        disconnectButton.setEnabled(false);
        connectButton.addActionListener(e -> listener.onConnect());
        disconnectButton.addActionListener(e -> listener.onDisconnect());
    }

    private int addRow(GridBagConstraints gbc, int row, String label, java.awt.Component field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        add(field, gbc);
        return row + 1;
    }

    public ServerProfile buildProfileFromFields() {
        List<HeaderEntry> headers = new ArrayList<>();
        for (String line : headersArea.getText().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx == -1) {
                continue;
            }
            headers.add(new HeaderEntry(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim()));
        }

        String manualListener = manualListenerField.getText().trim();
        String manualHost = null;
        Integer manualPort = null;
        if (!manualListener.isEmpty() && manualListener.contains(":")) {
            int idx = manualListener.lastIndexOf(':');
            manualHost = manualListener.substring(0, idx);
            try {
                manualPort = Integer.parseInt(manualListener.substring(idx + 1));
            } catch (NumberFormatException ignored) {
            }
        }

        return new ServerProfile(
                currentProfileId,
                nameField.getText().trim().isEmpty() ? "Unnamed server" : nameField.getText().trim(),
                urlField.getText().trim(),
                (TransportMode) transportModeCombo.getSelectedItem(),
                headers,
                blankToNull(oauthClientIdField.getText()),
                blankToNull(oauthClientSecretField.getText()),
                bypassProxyCheckbox.isSelected(),
                manualHost,
                manualPort,
                createdAt,
                null
        );
    }

    public void populateFromProfile(ServerProfile profile) {
        currentProfileId = profile.id();
        createdAt = profile.createdAt();
        nameField.setText(profile.name());
        urlField.setText(profile.serverUrl());
        transportModeCombo.setSelectedItem(profile.transportMode());
        StringBuilder headerText = new StringBuilder();
        for (HeaderEntry h : profile.customHeaders()) {
            headerText.append(h.name()).append(": ").append(h.value()).append('\n');
        }
        headersArea.setText(headerText.toString());
        bypassProxyCheckbox.setSelected(profile.bypassBurpProxy());
        manualListenerField.setText(profile.manualListenerHost() != null && profile.manualListenerPort() != null
                ? profile.manualListenerHost() + ":" + profile.manualListenerPort() : "");
        oauthClientIdField.setText(profile.oauthClientId() != null ? profile.oauthClientId() : "");
        oauthClientSecretField.setText(profile.oauthClientSecret() != null ? profile.oauthClientSecret() : "");
    }

    public void newProfile() {
        currentProfileId = UUID.randomUUID().toString();
        createdAt = System.currentTimeMillis();
        nameField.setText("New server");
        urlField.setText("");
        transportModeCombo.setSelectedItem(TransportMode.AUTO);
        headersArea.setText("");
        bypassProxyCheckbox.setSelected(false);
        manualListenerField.setText("");
        oauthClientIdField.setText("");
        oauthClientSecretField.setText("");
    }

    public String currentProfileId() {
        return currentProfileId;
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public void setConnected(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
