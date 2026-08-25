package burpmcp.ui;

import burpmcp.protocol.model.CallToolResult;
import burpmcp.protocol.model.ContentBlock;
import burpmcp.protocol.model.Tool;
import burpmcp.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.List;

public final class ToolsPanel extends JPanel {

    public interface Listener {
        void onInvoke(Tool tool, JsonNode arguments);

        void onCancel();
    }

    private final DefaultListModel<Tool> listModel = new DefaultListModel<>();
    private final JList<Tool> toolList = new JList<>(listModel);
    private final JTextArea descriptionArea = new JTextArea(3, 30);
    private final JPanel formContainer = new JPanel(new BorderLayout());
    private final JButton invokeButton = new JButton("Invoke");
    private final JButton cancelButton = new JButton("Cancel");
    private final JLabel progressLabel = new JLabel(" ");
    private final JTextArea resultArea = new JTextArea();

    private final Listener listener;
    private SchemaFormBuilder.BuiltForm currentForm;

    public ToolsPanel(Listener listener) {
        super(new BorderLayout());
        this.listener = listener;

        toolList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value.displayName());
            label.setOpaque(true);
            label.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return label;
        });
        toolList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedTool();
            }
        });

        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);

        invokeButton.setEnabled(false);
        cancelButton.setEnabled(false);
        invokeButton.addActionListener(e -> doInvoke());
        cancelButton.addActionListener(e -> listener.onCancel());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonRow.add(invokeButton);
        buttonRow.add(cancelButton);
        buttonRow.add(progressLabel);

        resultArea.setEditable(false);
        resultArea.setLineWrap(false);

        JPanel detailPanel = new JPanel(new BorderLayout());
        JPanel topDetail = new JPanel(new BorderLayout());
        topDetail.add(new JScrollPane(descriptionArea), BorderLayout.NORTH);
        topDetail.add(formContainer, BorderLayout.CENTER);
        topDetail.add(buttonRow, BorderLayout.SOUTH);

        JSplitPane detailSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topDetail, new JScrollPane(resultArea));
        detailSplit.setResizeWeight(0.5);
        detailPanel.add(detailSplit, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(toolList), detailPanel);
        mainSplit.setDividerLocation(220);
        add(mainSplit, BorderLayout.CENTER);
    }

    public void setTools(List<Tool> tools) {
        listModel.clear();
        tools.forEach(listModel::addElement);
        resultArea.setText("");
        if (!tools.isEmpty()) {
            toolList.setSelectedIndex(0);
        }
    }

    public void clear() {
        listModel.clear();
        formContainer.removeAll();
        formContainer.revalidate();
        formContainer.repaint();
        descriptionArea.setText("");
        resultArea.setText("");
        invokeButton.setEnabled(false);
        cancelButton.setEnabled(false);
    }

    private void showSelectedTool() {
        Tool tool = toolList.getSelectedValue();
        formContainer.removeAll();
        if (tool == null) {
            descriptionArea.setText("");
            invokeButton.setEnabled(false);
            currentForm = null;
        } else {
            descriptionArea.setText(tool.description() != null ? tool.description() : "(no description)");
            currentForm = SchemaFormBuilder.build(tool.inputSchema());
            formContainer.add(new JScrollPane(currentForm.component), BorderLayout.CENTER);
            invokeButton.setEnabled(true);
        }
        formContainer.revalidate();
        formContainer.repaint();
        resultArea.setText("");
    }

    private void doInvoke() {
        Tool tool = toolList.getSelectedValue();
        if (tool == null || currentForm == null) {
            return;
        }
        ObjectNode arguments;
        try {
            arguments = currentForm.readValues();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid arguments", JOptionPane.WARNING_MESSAGE);
            return;
        }
        resultArea.setForeground(Color.BLACK);
        resultArea.setText("");
        progressLabel.setText("Invoking...");
        setInvoking(true);
        listener.onInvoke(tool, arguments);
    }

    public void setInvoking(boolean invoking) {
        invokeButton.setEnabled(!invoking && toolList.getSelectedValue() != null);
        cancelButton.setEnabled(invoking);
        if (!invoking) {
            progressLabel.setText(" ");
        }
    }

    public void showProgress(JsonNode progressParams) {
        double progress = progressParams.path("progress").asDouble(0);
        String message = progressParams.path("message").asText(null);
        if (progressParams.has("total")) {
            double total = progressParams.path("total").asDouble();
            progressLabel.setText(String.format("Progress: %.0f / %.0f%s", progress, total, message != null ? " — " + message : ""));
        } else {
            progressLabel.setText("Progress: " + progress + (message != null ? " — " + message : ""));
        }
    }

    public void showResult(CallToolResult result) {
        setInvoking(false);
        StringBuilder sb = new StringBuilder();
        if (result.isError()) {
            sb.append("[Tool reported an error — isError: true]\n\n");
        }
        for (ContentBlock block : result.content()) {
            if ("text".equals(block.type()) && block.text() != null) {
                sb.append(block.text()).append('\n');
            } else {
                sb.append("[").append(block.type()).append(" content — not rendered, see raw JSON below]\n");
                sb.append(prettyPrint(block.data())).append('\n');
            }
        }
        if (result.structuredContent() != null) {
            sb.append("\n--- structuredContent ---\n").append(prettyPrint(result.structuredContent()));
        }
        resultArea.setForeground(result.isError() ? new Color(160, 0, 0) : Color.BLACK);
        resultArea.setText(sb.toString());
        resultArea.setCaretPosition(0);
    }

    public void showProtocolError(String message) {
        setInvoking(false);
        resultArea.setForeground(new Color(200, 0, 0));
        resultArea.setText("[MCP protocol error]\n\n" + message);
        resultArea.setCaretPosition(0);
    }

    public void showTransportError(String message) {
        setInvoking(false);
        resultArea.setForeground(new Color(200, 0, 0));
        resultArea.setText("[Connection error]\n\n" + message);
        resultArea.setCaretPosition(0);
    }

    private static String prettyPrint(JsonNode node) {
        try {
            return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }
}
