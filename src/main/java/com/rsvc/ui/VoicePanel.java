package com.rsvc.ui;

import com.rsvc.network.VoiceConnection;

import net.runelite.client.ui.PluginPanel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/** Swing sidebar. Public update methods dispatch component changes to the EDT. */
public final class VoicePanel extends PluginPanel {
    private final JLabel status = new JLabel("Disconnected");
    private final JPanel people = new JPanel();
    private final JLabel mutedCount = new JLabel("0 muted");
    private final JLabel alias = new JLabel();
    private boolean connected;
    private boolean connectionActive;

    private final BiConsumer<Long, Boolean> block;
    private final BiConsumer<Long, Integer> volume;
    private final JButton connectButton =
            button("Connect", "Connect to nearby consenting voice users");
    private final JButton roomButton =
            button("Room", "Create/join a private room or return to nearby voice");
    private final JButton partyButton =
            button("Join party voice", "Join a party using Party or Hub Party Panel first");
    private boolean partyVoice;
    private boolean roomsAvailable;
    private Runnable partyAction = () -> {};
    private final JLabel scope = new JLabel("Nearby · 20 tiles");
    private final JCheckBox muted = new JCheckBox("Mute mic");
    private final JCheckBox deaf = new JCheckBox("Deafen");
    private String roomCode = "";
    private BiConsumer<String, String> roomAction = (action, code) -> {};

    public VoicePanel(
            Runnable connect,
            Runnable disconnect,
            Consumer<Boolean> mute,
            Consumer<Boolean> deafen,
            BiConsumer<Long, Boolean> block,
            Runnable clearBlocks) {
        this(connect, disconnect, mute, deafen, block, (id, level) -> {}, clearBlocks, () -> {});
    }

    public VoicePanel(
            Runnable connect,
            Runnable disconnect,
            Consumer<Boolean> mute,
            Consumer<Boolean> deafen,
            BiConsumer<Long, Boolean> block,
            BiConsumer<Long, Integer> volume,
            Runnable clearBlocks,
            Runnable devices) {
        this.block = block;
        this.volume = volume;
        setLayout(new BorderLayout(0, 6));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JButton info = button("Info", "Voice controls");
        info.addActionListener(
                e ->
                        JOptionPane.showMessageDialog(
                                this,
                                "Nearby voice: within 20 tiles, same world and floor.\n"
                                        + "Choose push-to-talk or voice activation in settings.\n"
                                        + "Room: create or join a private voice room.\n"
                                        + "For party use Join party voice instead.\n"
                                        + "Default hotkeys: F8 toggles mic mute; F9 toggles deafen.\n"
                                        + "Change hotkeys in settings; use them with game focus.\n",
                                "Voice information",
                                JOptionPane.INFORMATION_MESSAGE));
        addRow(top, row(new JLabel("Voice chat"), info));
        status.setIcon(
                new Icon() {
                    public int getIconWidth() {
                        return 10;
                    }

                    public int getIconHeight() {
                        return 10;
                    }

                    public void paintIcon(Component c, Graphics g, int x, int y) {
                        g.setColor(
                                connected
                                        ? new Color(85, 200, 130)
                                        : connectionActive ? new Color(225, 175, 70) : Color.GRAY);
                        g.fillOval(x + 1, y + 1, 8, 8);
                    }
                });
        status.setFont(status.getFont().deriveFont(11f));
        addRow(top, status);
        connectButton.addActionListener(
                e -> {
                    connectionActive = true;
                    connectButton.setEnabled(false);
                    status.setText("Connecting…");
                    refreshIndicator();
                    connect.run();
                });
        JButton leave = button("Disconnect", "Disconnect from voice");
        leave.addActionListener(e -> disconnect.run());
        addRow(top, row(connectButton, leave));
        muted.setToolTipText("Mute your microphone for everyone");
        muted.addActionListener(e -> mute.accept(muted.isSelected()));
        deaf.setToolTipText("Mute playback and microphone");
        deaf.addActionListener(e -> deafen.accept(deaf.isSelected()));
        addRow(top, row(muted, deaf));
        scope.setFont(scope.getFont().deriveFont(11f));
        scope.setHorizontalAlignment(JLabel.CENTER);
        addRow(top, scope);
        roomButton.setEnabled(false);
        roomButton.addActionListener(e -> roomMenu());
        addRow(top, roomButton);
        partyButton.setEnabled(false);
        partyButton.addActionListener(e -> partyAction.run());
        addRow(top, partyButton);
        alias.setVisible(false);
        alias.setFont(alias.getFont().deriveFont(11f));
        addRow(top, alias);
        add(top, BorderLayout.NORTH);
        people.setLayout(new BoxLayout(people, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(people);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createTitledBorder("Voices"));
        scroll.setPreferredSize(new Dimension(0, 130));
        scroll.setMinimumSize(new Dimension(0, 60));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
        JButton clear = button("Clear mutes", "Unmute all participants muted in this session");
        clear.addActionListener(e -> clearBlocks.run());
        JButton deviceButton = button("Devices", "Choose your microphone and speakers");
        deviceButton.addActionListener(e -> devices.run());
        JPanel bottom = new JPanel(new BorderLayout(0, 3));
        mutedCount.setFont(mutedCount.getFont().deriveFont(11f));
        mutedCount.setVisible(false);
        bottom.add(mutedCount, BorderLayout.NORTH);
        bottom.add(row(clear, deviceButton), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        peers(java.util.Collections.emptyList());
    }

    private static void addRow(JPanel parent, JComponent child) {
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        child.setMaximumSize(new Dimension(Integer.MAX_VALUE, child.getPreferredSize().height));
        parent.add(child);
        parent.add(Box.createVerticalStrut(4));
    }

    private static JPanel row(JComponent left, JComponent right) {
        JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
        row.add(left);
        row.add(right);
        return row;
    }

    private static JButton button(String label, String tooltip) {
        JButton button = new JButton(label);
        button.setToolTipText(tooltip);
        button.setFont(button.getFont().deriveFont(12f));
        button.setMargin(new Insets(2, 6, 2, 6));
        button.setPreferredSize(new Dimension(80, 24));
        return button;
    }

    public void status(String message) {
        SwingUtilities.invokeLater(
                () -> {
                    status.setText(
                            "<html><div style='width:150px'>"
                                    + escape(message.split(" — ", 2)[0])
                                    + "</div></html>");
                    status.setToolTipText(message);
                    status.setMaximumSize(
                            new Dimension(Integer.MAX_VALUE, status.getPreferredSize().height));
                    revalidate();
                });
    }

    public void mutedCount(int count) {
        SwingUtilities.invokeLater(
                () -> {
                    mutedCount.setText(count + " muted");
                    mutedCount.setVisible(count > 0);
                });
    }

    public void roomActions(BiConsumer<String, String> action) {
        roomAction = action;
    }

    public void partyAction(Runnable action) {
        partyAction = action;
    }

    public void partyState(boolean inParty, boolean selected, boolean ready) {
        SwingUtilities.invokeLater(
                () -> {
                    partyVoice = selected;
                    partyButton.setText(selected ? "Leave party voice" : "Join party voice");
                    partyButton.setEnabled(selected || (inParty && roomsAvailable));
                    partyButton.setToolTipText(
                            selected
                                    ? "Return to nearby voice with your microphone muted"
                                    : inParty
                                            ? "Replace nearby/private voice with your party's voice"
                                                  + " room"
                                            : "Join a party using Party or Hub Party Panel first");
                    roomButton.setEnabled(roomsAvailable && !selected);
                    updateScope();
                    if (selected && !ready) {
                        scope.setText("Joining party…");
                    }
                });
    }

    private void updateScope() {
        scope.setText(
                partyVoice
                        ? "Party voice"
                        : roomCode.isEmpty() ? "Nearby · 20 tiles" : "Private room");
        scope.setToolTipText(
                partyVoice
                        ? "Exclusive party voice; nearby and private-room audio are off"
                        : roomCode.isEmpty()
                                ? "Within 20 tiles, same world and floor"
                                : "Room members only, regardless of distance, world or floor");
    }

    public void roomState(String code) {
        roomState(code, false);
    }

    public void roomState(String code, boolean created) {
        SwingUtilities.invokeLater(
                () -> {
                    roomCode = code;
                    updateScope();
                    // The relay can send an updated roster before the creation confirmation.
                    if (created && !code.isEmpty() && !partyVoice) {
                        showInviteCode();
                    }
                });
    }

    public void roomsEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            roomsAvailable = enabled;
            roomButton.setEnabled(enabled && !partyVoice);
            if (!enabled && !partyVoice) {
                partyButton.setEnabled(false);
            }
        });
    }

    public void roomExpired() {
        SwingUtilities.invokeLater(
                () -> {
                    scope.setText("Room expired");
                    scope.setToolTipText(
                            "Returned to nearby voice; microphone muted until you unmute");
                });
    }

    public void microphoneMuted(boolean value) {
        SwingUtilities.invokeLater(() -> muted.setSelected(value));
    }

    public void deafened(boolean value) {
        SwingUtilities.invokeLater(() -> deaf.setSelected(value));
    }

    private void roomMenu() {
        String[] choices =
                roomCode.isEmpty()
                        ? new String[] {"Create", "Join", "Cancel"}
                        : new String[] {"Invite code", "Leave room", "Cancel"};
        int choice =
                javax.swing.JOptionPane.showOptionDialog(
                        this,
                        roomCode.isEmpty()
                                ? "Creating or joining a room leaves 20-tile nearby voice.\n"
                                      + "Only room members hear each other, at any distance, world"
                                      + " or floor.\n"
                                      + "Stay logged in and connected; mute controls still"
                                      + " apply.\n\n"
                                      + "The room stays open until its last connection leaves."
                                : "Room members only, at any distance, world or floor.\n"
                                        + "Leaving returns you to 20-tile nearby voice.",
                        "Voice room",
                        javax.swing.JOptionPane.DEFAULT_OPTION,
                        javax.swing.JOptionPane.PLAIN_MESSAGE,
                        null,
                        choices,
                        choices[0]);
        if (roomCode.isEmpty()) {
            if (choice == 0) {
                roomAction.accept("create", "");
            }
            if (choice == 1) {
                String code = javax.swing.JOptionPane.showInputDialog(this, "Invite code:");
                if (code != null) {
                    roomAction.accept("join", code.trim());
                }
            }
        } else if (choice == 0) {
            showInviteCode();
        } else if (choice == 1) {
            roomAction.accept("leave", "");
        }
    }

    private void showInviteCode() {
        if (roomCode.isEmpty()) {
            return;
        }
        javax.swing.JTextField invite = new javax.swing.JTextField(roomCode);
        invite.setEditable(false);
        SwingUtilities.invokeLater(
                () -> {
                    invite.requestFocusInWindow();
                    invite.selectAll();
                });
        JOptionPane.showMessageDialog(
                this, invite, "Select and copy invite code", JOptionPane.PLAIN_MESSAGE);
    }

    public void connectionActive(boolean active) {
        SwingUtilities.invokeLater(
                () -> {
                    connectionActive = active;
                    if (!active) {
                        connected = false;
                    }
                    connectButton.setEnabled(!active);
                    refreshIndicator();
                });
    }

    /** Confirmed socket state, separate from a pending connection/retry. */
    public void connected(boolean value) {
        SwingUtilities.invokeLater(
                () -> {
                    connected = value;
                    refreshIndicator();
                });
    }

    private void refreshIndicator() {
        status.getAccessibleContext()
                .setAccessibleDescription(
                        connected
                                ? "Connected"
                                : connectionActive ? "Connecting or retrying" : "Disconnected");
        status.repaint();
    }

    public void alias(String value) {
        SwingUtilities.invokeLater(
                () -> {
                    alias.setText("You: " + value);
                    alias.setVisible(!value.isEmpty());
                    alias.setMaximumSize(
                            new Dimension(Integer.MAX_VALUE, alias.getPreferredSize().height));
                    revalidate();
                });
    }

    public void peers(List<VoiceConnection.Peer> peers) {
        peers(peers, java.util.Collections.emptyMap(), java.util.Collections.emptySet());
    }

    public void peers(
            List<VoiceConnection.Peer> peers,
            java.util.Map<String, Integer> levels,
            java.util.Set<String> muted) {
        SwingUtilities.invokeLater(
                () -> {
                    people.removeAll();
                    for (VoiceConnection.Peer peer : peers) {
                        JPanel row = new JPanel(new BorderLayout(4, 0));
                        row.setAlignmentX(Component.LEFT_ALIGNMENT);
                        JPanel heading = new JPanel(new BorderLayout(4, 0));
                        heading.add(new JLabel(peer.name), BorderLayout.CENTER);
                        JToggleButton mute = new JToggleButton("Mute", muted.contains(peer.name));
                        mute.setToolTipText("Mute/unmute this voice only for you");
                        mute.setMargin(new Insets(1, 4, 1, 4));
                        mute.setPreferredSize(new Dimension(58, 23));
                        mute.addActionListener(e -> block.accept(peer.id, mute.isSelected()));
                        heading.add(mute, BorderLayout.EAST);
                        row.add(heading, BorderLayout.NORTH);
                        JSlider slider = new JSlider(0, 200, levels.getOrDefault(peer.name, 100));
                        slider.setPreferredSize(new Dimension(105, 20));
                        slider.setMinimumSize(new Dimension(20, 20));
                        slider.setToolTipText("Playback for you: 0–200%; 100% is normal");
                        JLabel level = new JLabel(slider.getValue() + "%");
                        level.setPreferredSize(new Dimension(38, 20));
                        slider.addChangeListener(
                                e -> {
                                    level.setText(slider.getValue() + "%");
                                    if (!slider.getValueIsAdjusting()) {
                                        volume.accept(peer.id, slider.getValue());
                                    }
                                });
                        JPanel adjustment = new JPanel(new BorderLayout());
                        adjustment.add(slider, BorderLayout.CENTER);
                        adjustment.add(level, BorderLayout.EAST);
                        row.add(adjustment, BorderLayout.CENTER);
                        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 49));
                        people.add(row);
                        people.add(Box.createVerticalStrut(5));
                    }
                    if (peers.isEmpty()) {
                        people.add(new JLabel("No other voices"));
                    }
                    people.revalidate();
                    people.repaint();
                });
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
