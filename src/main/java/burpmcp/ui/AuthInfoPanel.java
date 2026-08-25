package burpmcp.ui;

import burpmcp.oauth.AuthDiagnostics;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Mirrors MCP Inspector's "Connection Info" panel: discovered AS metadata, registered client,
 * granted scopes, token status, with explicit Sign-in / Clear-state actions.
 */
public final class AuthInfoPanel extends JPanel {

    private final JLabel statusLabel = new JLabel("Not connected");
    private final JLabel issuerLabel = new JLabel("-");
    private final JLabel clientIdLabel = new JLabel("-");
    private final JLabel scopeLabel = new JLabel("-");
    private final JLabel expiresLabel = new JLabel("-");
    private final JButton signInButton = new JButton("Sign in...");
    private final JButton clearButton = new JButton("Clear OAuth state / Re-login");

    public AuthInfoPanel(Runnable onSignIn, Runnable onClearState) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder("Authorization"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addRow(gbc, row++, "Status:", statusLabel);
        addRow(gbc, row++, "Issuer:", issuerLabel);
        addRow(gbc, row++, "Client ID:", clientIdLabel);
        addRow(gbc, row++, "Scope:", scopeLabel);
        addRow(gbc, row++, "Token expires:", expiresLabel);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        JPanel buttons = new JPanel();
        signInButton.addActionListener(e -> onSignIn.run());
        clearButton.addActionListener(e -> onClearState.run());
        buttons.add(signInButton);
        buttons.add(clearButton);
        add(buttons, gbc);

        signInButton.setVisible(false);
        clearButton.setVisible(false);
    }

    private void addRow(GridBagConstraints gbc, int row, String label, JLabel valueLabel) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        add(valueLabel, gbc);
    }

    public void showSignInRequired() {
        statusLabel.setText("Authorization required");
        signInButton.setVisible(true);
        clearButton.setVisible(false);
    }

    public void update(AuthDiagnostics diagnostics) {
        signInButton.setVisible(false);
        if (diagnostics == null || !diagnostics.hasToken()) {
            statusLabel.setText("No token");
            issuerLabel.setText("-");
            clientIdLabel.setText("-");
            scopeLabel.setText("-");
            expiresLabel.setText("-");
            clearButton.setVisible(false);
            return;
        }
        statusLabel.setText(diagnostics.tokenExpired() ? "Token expired" : "Signed in");
        issuerLabel.setText(diagnostics.issuer() != null ? diagnostics.issuer() : "-");
        clientIdLabel.setText(diagnostics.clientId() != null ? diagnostics.clientId() : "-");
        scopeLabel.setText(diagnostics.scope() != null ? diagnostics.scope() : "(default)");
        expiresLabel.setText(diagnostics.expiresAtEpochMillis() != null
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(diagnostics.expiresAtEpochMillis()))
                : "-");
        clearButton.setVisible(true);
    }

    public void reset() {
        statusLabel.setText("Not connected");
        issuerLabel.setText("-");
        clientIdLabel.setText("-");
        scopeLabel.setText("-");
        expiresLabel.setText("-");
        signInButton.setVisible(false);
        clearButton.setVisible(false);
    }
}
