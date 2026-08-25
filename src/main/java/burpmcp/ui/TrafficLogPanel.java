package burpmcp.ui;

import burpmcp.protocol.TrafficDirection;
import burpmcp.transport.HttpExchangeLog;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Persistent, in-memory-only (never written to the Burp project file, since it contains bearer
 * tokens) log of every JSON-RPC message and raw HTTP exchange. Complements Burp's own Proxy
 * History — this adds JSON-RPC-level semantics on top of what Proxy History shows as raw HTTP.
 */
public final class TrafficLogPanel extends JPanel {

    private static final int MAX_ENTRIES = 5000;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    private record Row(long timestamp, String tag, String direction, String summary, String detail) {
    }

    private final List<Row> rows = new ArrayList<>();
    private final RowTableModel tableModel = new RowTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea detailArea = new JTextArea();

    public TrafficLogPanel() {
        super(new BorderLayout());

        table.setAutoCreateRowSorter(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int row = table.getSelectedRow();
            detailArea.setText(row >= 0 && row < rows.size() ? rows.get(row).detail() : "");
            detailArea.setCaretPosition(0);
        });

        detailArea.setEditable(false);
        detailArea.setLineWrap(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detailArea));
        split.setResizeWeight(0.5);

        JPanel toolbar = new JPanel();
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clear());
        JButton exportButton = new JButton("Export to file...");
        exportButton.addActionListener(e -> export());
        toolbar.add(clearButton);
        toolbar.add(exportButton);

        add(toolbar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        setPreferredSize(new java.awt.Dimension(500, 600));
    }

    public void logJsonRpc(TrafficDirection direction, String tag, String rawJson) {
        String summary = summarizeJsonRpc(rawJson);
        UiEventBridge.post(() -> addRow(new Row(System.currentTimeMillis(), tag, direction.name(), summary, rawJson)));
    }

    public void logHttpExchange(HttpExchangeLog log) {
        String summary = log.method() + " " + log.url() + " -> " + log.statusCode();
        String detail = formatHttpDetail(log);
        UiEventBridge.post(() -> addRow(new Row(System.currentTimeMillis(), log.tag(), "HTTP", summary, detail)));
    }

    private void addRow(Row row) {
        rows.add(row);
        if (rows.size() > MAX_ENTRIES) {
            rows.remove(0);
        }
        tableModel.fireTableDataChanged();
        int last = rows.size() - 1;
        if (last >= 0) {
            table.scrollRectToVisible(table.getCellRect(last, 0, true));
        }
    }

    private void clear() {
        UiEventBridge.post(() -> {
            rows.clear();
            tableModel.fireTableDataChanged();
            detailArea.setText("");
        });
    }

    private void export() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("mcp-traffic-log.txt"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try (FileWriter writer = new FileWriter(chooser.getSelectedFile())) {
            for (Row row : rows) {
                writer.write("[" + TIME_FORMAT.format(new Date(row.timestamp())) + "] " + row.tag() + " " + row.direction() + " " + row.summary() + "\n");
                writer.write(row.detail());
                writer.write("\n\n");
            }
            JOptionPane.showMessageDialog(this, "Exported " + rows.size() + " entries.");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String summarizeJsonRpc(String rawJson) {
        try {
            var node = burpmcp.util.Json.MAPPER.readTree(rawJson);
            if (node.has("method")) {
                String idPart = node.has("id") ? " (id=" + node.get("id").asText() + ")" : "";
                return node.get("method").asText() + idPart;
            }
            if (node.has("error")) {
                return "error " + node.path("error").path("code").asText() + ": " + node.path("error").path("message").asText();
            }
            if (node.has("result")) {
                return "result (id=" + node.path("id").asText() + ")";
            }
            return "message";
        } catch (Exception e) {
            return "(unparseable)";
        }
    }

    private static String formatHttpDetail(HttpExchangeLog log) {
        StringBuilder sb = new StringBuilder();
        sb.append(log.method()).append(' ').append(log.url()).append('\n');
        log.requestHeaders().forEach((k, v) -> sb.append(k).append(": ").append(String.join(", ", v)).append('\n'));
        if (log.requestBody() != null) {
            sb.append('\n').append(log.requestBody()).append('\n');
        }
        sb.append("\n--- response ").append(log.statusCode()).append(" ---\n");
        log.responseHeaders().forEach((k, v) -> sb.append(k).append(": ").append(String.join(", ", v)).append('\n'));
        return sb.toString();
    }

    private final class RowTableModel extends AbstractTableModel {
        private final String[] columns = {"Time", "Tag", "Dir", "Summary"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> TIME_FORMAT.format(new Date(row.timestamp()));
                case 1 -> row.tag();
                case 2 -> row.direction();
                case 3 -> row.summary();
                default -> "";
            };
        }
    }
}
