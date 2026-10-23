package com.rsvc;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

public final class VoicePanel extends PluginPanel
{
    private final JLabel status = new JLabel("Disconnected");
    private final JPanel people = new JPanel();
    private final JTextField channel = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JLabel mutedCount = new JLabel("0 muted");
    private final BiConsumer<Long, Boolean> block;

    public VoicePanel(BiConsumer<String, String> connect, Runnable disconnect, Consumer<Boolean> mute,
        Consumer<Boolean> deafen, BiConsumer<Long, Boolean> block, Runnable clearBlocks)
    {
        this(connect, disconnect, mute, deafen, block, clearBlocks, () -> {});
    }

    public VoicePanel(BiConsumer<String, String> connect, Runnable disconnect, Consumer<Boolean> mute,
        Consumer<Boolean> deafen, BiConsumer<Long, Boolean> block, Runnable clearBlocks, Runnable diagnostics)
    {
        this.block = block;
        setLayout(new BorderLayout(0, 6));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        addRow(top, new JLabel("Private voice"));
        status.setFont(status.getFont().deriveFont(11f)); addRow(top, status);
        JTextArea help = text("Your private channel, wherever you are.\nLog in and hold push-to-talk to speak.");
        help.setFont(help.getFont().deriveFont(11f)); addRow(top, help);
        addRow(top, new JLabel("Channel"));
        channel.setToolTipText("Ask the relay owner for a channel (3–32 letters, numbers, _ or -).");
        field(top, channel);
        addRow(top, new JLabel("Password"));
        password.setToolTipText("Required: 6–128 printable characters, no spaces. Use a separate voice password. Not saved.");
        field(top, password);
        JButton join = button("Join", "Join this private channel");
        join.addActionListener(e -> {
            char[] value = password.getPassword();
            String key = new String(value); java.util.Arrays.fill(value, '\0');
            try {
                String selected = VoiceConnection.validateChannel(channel.getText()); VoiceConnection.validatePassword(key);
                password.setText(""); connect.accept(selected, key);
            } catch (IllegalArgumentException invalid) { status(invalid.getMessage()); }
        });
        JButton leave = button("Leave", "Disconnect and forget the session password");
        leave.addActionListener(e -> { password.setText(""); disconnect.run(); });
        addRow(top, row(join, leave));
        JCheckBox muted = new JCheckBox("Mute"); muted.setToolTipText("Mute microphone");
        muted.addActionListener(e -> mute.accept(muted.isSelected()));
        JCheckBox deaf = new JCheckBox("Deafen"); deaf.setToolTipText("Mute playback and microphone");
        deaf.addActionListener(e -> deafen.accept(deaf.isSelected()));
        addRow(top, row(muted, deaf));
        JLabel identities = new JLabel("Names are not verified RS identities.");
        identities.setFont(identities.getFont().deriveFont(10f)); addRow(top, identities);
        add(top, BorderLayout.NORTH);

        people.setLayout(new BoxLayout(people, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(people);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createTitledBorder("In your channel"));
        scroll.setPreferredSize(new Dimension(0, 130)); scroll.setMinimumSize(new Dimension(0, 60));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);

        JButton clear = button("Clear mutes", "Unmute all participants muted in this session");
        clear.addActionListener(e -> clearBlocks.run());
        JButton devices = button("Devices", "Show audio device names for plugin settings");
        devices.addActionListener(e -> {
            JTextArea names = text("MICROPHONES\n" + String.join("\n", AudioEngine.devices(true)) + "\n\nSPEAKERS\n" + String.join("\n", AudioEngine.devices(false)) + "\n\nCopy a name into plugin settings, or leave blank for the system default.");
            javax.swing.JOptionPane.showMessageDialog(this, names, "Audio devices", javax.swing.JOptionPane.PLAIN_MESSAGE);
        });
        JPanel bottom = new JPanel(new BorderLayout(0, 3));
        mutedCount.setFont(mutedCount.getFont().deriveFont(11f));
        JButton stats = button("Stats / test", "Measure traffic and optionally record your own mic locally");
        stats.addActionListener(e -> diagnostics.run());
        bottom.add(mutedCount, BorderLayout.NORTH); bottom.add(row(clear, devices), BorderLayout.CENTER); bottom.add(stats, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        peers(java.util.Collections.emptyList());
    }

    private static void addRow(JPanel parent, JComponent child)
    {
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        child.setMaximumSize(new Dimension(Integer.MAX_VALUE, child.getPreferredSize().height));
        parent.add(child); parent.add(Box.createVerticalStrut(4));
    }
    private static void field(JPanel parent, JTextField field)
    {
        field.setPreferredSize(new Dimension(180, 25)); addRow(parent, field);
    }
    private static JPanel row(JComponent left, JComponent right)
    {
        JPanel row = new JPanel(new GridLayout(1, 2, 4, 0)); row.add(left); row.add(right); return row;
    }
    private static JButton button(String label, String tooltip)
    {
        JButton button = new JButton(label); button.setToolTipText(tooltip);
        button.setFont(button.getFont().deriveFont(12f)); button.setMargin(new Insets(2, 6, 2, 6));
        button.setPreferredSize(new Dimension(80, 24)); return button;
    }
    private static JTextArea text(String value)
    {
        JTextArea area = new JTextArea(value); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setOpaque(false); area.setForeground(javax.swing.UIManager.getColor("Label.foreground")); area.setColumns(20); return area;
    }
    public void status(String message)
    {
        SwingUtilities.invokeLater(() -> {
            status.setText("<html><div style='width:150px'>" + escape(message) + "</div></html>");
            status.setMaximumSize(new Dimension(Integer.MAX_VALUE, status.getPreferredSize().height)); revalidate();
        });
    }
    public void mutedCount(int count) { SwingUtilities.invokeLater(() -> mutedCount.setText(count + " muted this session")); }
    public void peers(List<VoiceConnection.Peer> peers)
    {
        SwingUtilities.invokeLater(() -> {
            people.removeAll();
            for (VoiceConnection.Peer peer : peers)
            {
                JPanel row = new JPanel(new BorderLayout(4, 0)); row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.add(new JLabel(peer.name), BorderLayout.CENTER);
                JButton mute = button("Mute", "Mute " + peer.name); mute.setPreferredSize(new Dimension(50, 23));
                mute.addActionListener(e -> block.accept(peer.id, true)); row.add(mute, BorderLayout.EAST);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25)); people.add(row); people.add(Box.createVerticalStrut(3));
            }
            if (peers.isEmpty()) people.add(new JLabel("Nobody else in channel"));
            people.revalidate(); people.repaint();
        });
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}

