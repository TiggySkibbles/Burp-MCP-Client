package burpmcp.ui;

import burpmcp.persistence.ServerProfile;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

public final class ProfileListPanel extends JPanel {

    public interface Listener {
        void onProfileSelected(ServerProfile profile);

        void onNewProfile();

        void onSaveProfile();

        void onDeleteProfile(ServerProfile profile);
    }

    private final DefaultListModel<ServerProfile> model = new DefaultListModel<>();
    private final JList<ServerProfile> list = new JList<>(model);

    public ProfileListPanel(Listener listener) {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Saved servers"));

        list.setCellRenderer((jList, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value.name());
            label.setOpaque(true);
            label.setBackground(isSelected ? jList.getSelectionBackground() : jList.getBackground());
            label.setForeground(isSelected ? jList.getSelectionForeground() : jList.getForeground());
            return label;
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                listener.onProfileSelected(list.getSelectedValue());
            }
        });

        JButton newButton = new JButton("New");
        JButton saveButton = new JButton("Save");
        JButton deleteButton = new JButton("Delete");
        newButton.addActionListener(e -> {
            list.clearSelection();
            listener.onNewProfile();
        });
        saveButton.addActionListener(e -> listener.onSaveProfile());
        deleteButton.addActionListener(e -> {
            ServerProfile selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(this, "Delete profile '" + selected.name() + "'?",
                    "Confirm delete", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                listener.onDeleteProfile(selected);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(newButton);
        buttons.add(saveButton);
        buttons.add(deleteButton);

        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setPreferredSize(new java.awt.Dimension(180, 200));
    }

    public void setProfiles(List<ServerProfile> profiles) {
        ServerProfile selected = list.getSelectedValue();
        model.clear();
        profiles.forEach(model::addElement);
        if (selected != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).id().equals(selected.id())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }
}
