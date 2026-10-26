package com.rsvc.ui;

import com.rsvc.network.VoiceConnection;

import net.runelite.client.ui.PluginPanel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.font.TextAttribute;
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
    private final java.util.concurrent.atomic.AtomicReference<PeerView> pendingPeers =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean peerRefreshQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final javax.swing.Timer peerRefresh = new javax.swing.Timer(1000, e -> flushPeers());
    private final java.util.Map<Long, JLabel> peerStatusLabels = new java.util.HashMap<>();
    private PeerView displayedPeers;
    private volatile boolean closed;
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
    private final JLabel roomFeedback = new JLabel();
    private final JPanel recoveryOptions = new JPanel();
    private final JButton retryRoom = button("Retry room", "Try the same private room again");
    private final JButton nearbyVoice = button("Return to nearby", "Resume nearby voice with your current mic setting");
    private boolean recoveryPaused;
    private Runnable retryRoomAction = () -> {};
    private Runnable nearbyVoiceAction = () -> {};
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
        partyButton.addActionListener(
                e -> {
                    roomFeedback.setVisible(false);
                    partyAction.run();
                });
        addRow(top, partyButton);
        roomFeedback.setVisible(false);
        roomFeedback.setFont(roomFeedback.getFont().deriveFont(11f));
        addRow(top, roomFeedback);
        recoveryOptions.setLayout(new BoxLayout(recoveryOptions, BoxLayout.Y_AXIS));
        JLabel recoveryHelp = new JLabel("<html>Couldn’t rejoin room.<br>Choose how to resume voice.</html>");
        recoveryHelp.setFont(recoveryHelp.getFont().deriveFont(11f));
        addRow(recoveryOptions, recoveryHelp);
        addRow(recoveryOptions, retryRoom);
        addRow(recoveryOptions, nearbyVoice);
        retryRoom.addActionListener(e -> {
            if (closed) {
                return;
            }
            retryRoom.setEnabled(false);
            nearbyVoice.setEnabled(false);
            retryRoomAction.run();
        });
        nearbyVoice.addActionListener(e -> {
            if (closed) {
                return;
            }
            retryRoom.setEnabled(false);
            nearbyVoice.setEnabled(false);
            nearbyVoiceAction.run();
        });
        recoveryOptions.setVisible(false);
        addRow(top, recoveryOptions);
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
        peerRefresh.setRepeats(false);
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
                    String displayed = recoveryPaused
                            ? "Voice paused — retry room or return to nearby" : message;
                    status.setText(
                            "<html><div style='width:150px'>"
                                    + escape(displayed.split(" — ", 2)[0])
                                    + "</div></html>");
                    status.setToolTipText(displayed);
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

    public void roomRecoveryActions(Runnable retry, Runnable nearby) {
        retryRoomAction = retry;
        nearbyVoiceAction = nearby;
    }

    public void roomRecovery(boolean paused) {
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            recoveryPaused = paused;
            recoveryOptions.setVisible(paused);
            retryRoom.setEnabled(paused && roomsAvailable);
            nearbyVoice.setEnabled(paused && roomsAvailable);
            if (paused) {
                roomFeedback.setVisible(false);
            }
            updateScope();
            revalidate();
            repaint();
        });
    }

    public void partyState(boolean inParty, boolean selected, boolean ready) {
        SwingUtilities.invokeLater(
                () -> {
                    partyVoice = selected;
                    partyButton.setText(selected ? "Leave party voice" : "Join party voice");
                    partyButton.setEnabled(selected || (inParty && roomsAvailable));
                    partyButton.setToolTipText(
                            selected
                                    ? "Return to nearby voice; keep your mute setting"
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
                recoveryPaused
                        ? "Voice paused"
                        : partyVoice
                        ? "Party voice"
                        : roomCode.isEmpty() ? "Nearby · 20 tiles" : "Private room");
        scope.setToolTipText(
                recoveryPaused
                        ? "No audio is sent or played until you retry the room or return to nearby"
                        : partyVoice
                        ? "Exclusive party voice; nearby and private-room audio are off"
                        : roomCode.isEmpty()
                                ? "Within 20 tiles, same world, floor and region; up to 25 per"
                                      + " group"
                                : "Room members only, regardless of distance, world or floor");
    }

    public void roomState(String code) {
        roomState(code, false);
    }

    public void roomState(String code, boolean created) {
        SwingUtilities.invokeLater(
                () -> {
                    if (!roomCode.equals(code) || created) {
                        roomFeedback.setVisible(false);
                    }
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
            retryRoom.setEnabled(recoveryPaused && enabled);
            nearbyVoice.setEnabled(recoveryPaused && enabled);
            roomButton.setEnabled(enabled && !partyVoice);
            if (!enabled && !partyVoice) {
                partyButton.setEnabled(false);
            }
        });
    }

    public void roomFull(boolean party) {
        SwingUtilities.invokeLater(
                () -> {
                    if (closed) {
                        return;
                    }
                    roomFeedback.setText(
                            "<html>"
                                    + (party ? "Party voice room full" : "Room full")
                                    + "<br>Try again later.</html>");
                    roomFeedback.setToolTipText(
                            "No automatic retry. Click to join again when a place opens.");
                    roomFeedback.setMaximumSize(
                            new Dimension(Integer.MAX_VALUE, roomFeedback.getPreferredSize().height));
                    roomFeedback.setVisible(true);
                    revalidate();
                });
    }

    public void microphoneMuted(boolean value) {
        SwingUtilities.invokeLater(() -> muted.setSelected(value));
    }

    public void deafened(boolean value) {
        SwingUtilities.invokeLater(() -> deaf.setSelected(value));
    }

    private void roomMenu() {
        roomFeedback.setVisible(false);
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
                        roomFeedback.setVisible(false);
                        recoveryPaused = false;
                        recoveryOptions.setVisible(false);
                        updateScope();
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
        if (closed) {
            return;
        }
        pendingPeers.set(new PeerView(peers, levels, muted));
        queuePeerRefresh();
    }

    private void queuePeerRefresh() {
        if (!closed && peerRefreshQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(
                    () -> {
                        if (!closed) {
                            peerRefresh.restart();
                        }
                    });
        }
    }

    // EDT only. One latest snapshot and one single-shot timer, never a toggle queue.
    void flushPeers() {
        peerRefresh.stop();
        PeerView next = pendingPeers.getAndSet(null);
        if (!closed && next != null) {
            renderPeers(next);
        }
        peerRefreshQueued.set(false);
        if (pendingPeers.get() != null) {
            queuePeerRefresh();
        }
    }

    public void close() {
        closed = true;
        pendingPeers.set(null);
        SwingUtilities.invokeLater(
                () -> {
                    peerRefresh.stop();
                    peerRefreshQueued.set(false);
                });
    }

    private void renderPeers(PeerView next) {
        List<VoiceConnection.Peer> peers = next.peers;
        java.util.Map<String, Integer> levels = next.levels;
        java.util.Set<String> muted = next.muted;
        boolean sameRows =
                displayedPeers != null
                        && displayedPeers.peers.size() == peers.size()
                        && displayedPeers.levels.equals(levels)
                        && displayedPeers.muted.equals(muted);
        for (int i = 0; sameRows && i < peers.size(); i++) {
            VoiceConnection.Peer old = displayedPeers.peers.get(i), peer = peers.get(i);
            sameRows = old.id == peer.id && old.name.equals(peer.name);
        }
        displayedPeers = next;
        if (sameRows) {
            for (VoiceConnection.Peer peer : peers) {
                updatePeerStatus(peerStatusLabels.get(peer.id), peer);
            }
            return;
        }
        people.removeAll();
        peerStatusLabels.clear();
        for (VoiceConnection.Peer peer : peers) {
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel heading = new JPanel(new BorderLayout(4, 0));
            JLabel name = new JLabel(peer.name);
            Font normalFont = name.getFont();
            Font mutedFont =
                    normalFont.deriveFont(
                            java.util.Collections.singletonMap(
                                    TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON));
            name.setFont(muted.contains(peer.name) ? mutedFont : normalFont);
            JLabel peerStatus = new JLabel();
            updatePeerStatus(peerStatus, peer);
            peerStatusLabels.put(peer.id, peerStatus);
            JPanel identity = new JPanel(new BorderLayout(3, 0));
            identity.add(name, BorderLayout.CENTER);
            identity.add(peerStatus, BorderLayout.EAST);
            heading.add(identity, BorderLayout.CENTER);
            JToggleButton mute = new JToggleButton("Mute", muted.contains(peer.name));
            mute.setToolTipText("Mute/unmute this voice only for you");
            mute.setMargin(new Insets(1, 4, 1, 4));
            mute.setPreferredSize(new Dimension(58, 23));
            mute.addActionListener(
                    e -> {
                        name.setFont(mute.isSelected() ? mutedFont : normalFont);
                        block.accept(peer.id, mute.isSelected());
                    });
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
    }

    private static void updatePeerStatus(JLabel label, VoiceConnection.Peer peer) {
        label.setIcon(PeerStatusIcon.of(peer.muted, peer.deaf));
        String description =
                peer.deaf
                        ? (peer.muted
                                ? "Self-muted and deafened"
                                : "Self-deafened (microphone also off)")
                        : peer.muted ? "Self-muted microphone" : null;
        label.setToolTipText(description);
        label.getAccessibleContext().setAccessibleDescription(description);
        label.setVisible(peer.muted || peer.deaf);
    }

    private static final class PeerView {
        final List<VoiceConnection.Peer> peers;
        final java.util.Map<String, Integer> levels;
        final java.util.Set<String> muted;

        PeerView(
                List<VoiceConnection.Peer> peers,
                java.util.Map<String, Integer> levels,
                java.util.Set<String> muted) {
            this.peers = List.copyOf(peers);
            this.levels = java.util.Map.copyOf(levels);
            this.muted = java.util.Set.copyOf(muted);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
