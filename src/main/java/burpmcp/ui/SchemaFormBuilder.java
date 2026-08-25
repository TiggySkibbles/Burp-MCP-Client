package burpmcp.ui;

import burpmcp.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds a Swing form from a tool's JSON-Schema {@code inputSchema}. Supports string/number/
 * integer/boolean/enum at the top level; anything deeper or unsupported (nested object/array,
 * oneOf/anyOf/$ref) falls back to a raw-JSON text field for that one property. If the schema
 * itself isn't a plain object-with-properties, the whole form falls back to one raw-JSON textarea.
 */
public final class SchemaFormBuilder {

    private SchemaFormBuilder() {
    }

    public static final class BuiltForm {
        public final JComponent component;
        private final Supplier<ObjectNode> reader;

        BuiltForm(JComponent component, Supplier<ObjectNode> reader) {
            this.component = component;
            this.reader = reader;
        }

        /** @throws IllegalArgumentException if a raw-JSON fallback field contains invalid JSON. */
        public ObjectNode readValues() {
            return reader.get();
        }
    }

    public static BuiltForm build(JsonNode schema) {
        if (schema == null || !"object".equals(schema.path("type").asText(null)) || !schema.has("properties")) {
            return wholeJsonFallback();
        }

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        Set<String> required = new HashSet<>();
        if (schema.has("required")) {
            schema.get("required").forEach(n -> required.add(n.asText()));
        }

        Map<String, Supplier<JsonNode>> fieldReaders = new LinkedHashMap<>();
        int row = 0;
        var it = schema.get("properties").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode propSchema = entry.getValue();
            String type = propSchema.path("type").asText("string");

            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            panel.add(new JLabel(name + (required.contains(name) ? " *" : "") + ":"), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1;

            if (propSchema.has("enum") && propSchema.get("enum").isArray()) {
                List<String> options = new ArrayList<>();
                propSchema.get("enum").forEach(n -> options.add(n.asText()));
                JComboBox<String> combo = new JComboBox<>(options.toArray(new String[0]));
                combo.setSelectedItem(null);
                panel.add(combo, gbc);
                fieldReaders.put(name, () -> combo.getSelectedItem() != null ? TextNode.valueOf((String) combo.getSelectedItem()) : null);
            } else if ("boolean".equals(type)) {
                JCheckBox checkbox = new JCheckBox();
                panel.add(checkbox, gbc);
                fieldReaders.put(name, () -> BooleanNode.valueOf(checkbox.isSelected()));
            } else if ("number".equals(type) || "integer".equals(type)) {
                JTextField field = new JTextField();
                panel.add(field, gbc);
                boolean isInteger = "integer".equals(type);
                fieldReaders.put(name, () -> parseNumeric(name, field.getText().trim(), isInteger));
            } else if ("object".equals(type) || "array".equals(type)
                    || propSchema.has("oneOf") || propSchema.has("anyOf") || propSchema.has("$ref")) {
                JTextArea area = new JTextArea(3, 30);
                panel.add(new JScrollPane(area), gbc);
                fieldReaders.put(name, () -> parseRawJsonField(name, area.getText().trim()));
            } else {
                JTextField field = new JTextField();
                panel.add(field, gbc);
                fieldReaders.put(name, () -> field.getText().isEmpty() ? null : TextNode.valueOf(field.getText()));
            }
            row++;
        }

        return new BuiltForm(panel, () -> {
            ObjectNode result = Json.MAPPER.createObjectNode();
            fieldReaders.forEach((name, reader) -> {
                JsonNode value = reader.get();
                if (value != null) {
                    result.set(name, value);
                }
            });
            return result;
        });
    }

    private static JsonNode parseNumeric(String fieldName, String text, boolean isInteger) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return isInteger ? LongNode.valueOf(Long.parseLong(text)) : DoubleNode.valueOf(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not a valid number: " + text);
        }
    }

    private static JsonNode parseRawJsonField(String fieldName, String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not valid JSON: " + e.getMessage());
        }
    }

    private static BuiltForm wholeJsonFallback() {
        JTextArea area = new JTextArea(10, 40);
        area.setText("{}");
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(new JLabel("Arguments (raw JSON — this tool's schema isn't a simple object):"), BorderLayout.NORTH);
        wrap.add(new JScrollPane(area), BorderLayout.CENTER);
        return new BuiltForm(wrap, () -> {
            String text = area.getText().trim();
            try {
                JsonNode node = Json.MAPPER.readTree(text.isEmpty() ? "{}" : text);
                if (!node.isObject()) {
                    throw new IllegalArgumentException("Arguments must be a JSON object");
                }
                return (ObjectNode) node;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
            }
        });
    }
}
