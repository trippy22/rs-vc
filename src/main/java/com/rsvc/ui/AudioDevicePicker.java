package com.rsvc.ui;

import com.rsvc.audio.AudioEngine;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Enumerates Java Sound devices off the UI/client threads; preserves existing exact-name settings.
 */
public final class AudioDevicePicker implements AutoCloseable {
    static final class Choice {
        final String value;
        private final boolean available;

        Choice(String value, boolean available) {
            this.value = value;
            this.available = available;
        }

        @Override
        public String toString() {
            return value.isEmpty() ? "Default" : value + (available ? "" : " (unavailable)");
        }
    }

    private final BiConsumer<String, String> save;
    private final ExecutorService scanner =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "RS VC audio device list");
                        thread.setDaemon(true);
                        return thread;
                    });
    private volatile boolean closed;
    private JDialog dialog; // EDT only.

    public AudioDevicePicker(BiConsumer<String, String> save) {
        this.save = save;
    }

    static DefaultComboBoxModel<Choice> choices(List<String> devices, String selected) {
        DefaultComboBoxModel<Choice> model = new DefaultComboBoxModel<>();
        model.addElement(new Choice("", true));
        for (String name : new LinkedHashSet<>(devices)) {
            if (!name.isEmpty()) {
                model.addElement(new Choice(name, true));
            }
        }
        if (!selected.isEmpty() && !devices.contains(selected)) {
            model.addElement(new Choice(selected, false));
        }
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).value.equals(selected)) {
                model.setSelectedItem(model.getElementAt(i));
            }
        }
        return model;
    }

    public void show(Component parent, String microphone, String speakers) {
        if (closed) {
            return;
        }
        if (dialog != null) {
            dialog.toFront();
            return;
        }
        JDialog window =
                new JDialog(
                        SwingUtilities.getWindowAncestor(parent),
                        "Audio devices",
                        Dialog.ModalityType.MODELESS);
        dialog = window;
        window.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JComboBox<Choice> mic =
                new JComboBox<>(choices(java.util.Collections.emptyList(), microphone));
        JComboBox<Choice> output =
                new JComboBox<>(choices(java.util.Collections.emptyList(), speakers));
        mic.setPreferredSize(new Dimension(360, 26));
        output.setPreferredSize(new Dimension(360, 26));
        JLabel status =
                new JLabel("Default follows your operating system. Apply keeps your voice room.");
        JButton refresh = new JButton("Refresh"),
                apply = new JButton("Apply"),
                cancel = new JButton("Cancel");
        JPanel fields = new JPanel(new GridLayout(4, 1, 0, 4));
        fields.add(new JLabel("Microphone"));
        fields.add(mic);
        fields.add(new JLabel("Speakers"));
        fields.add(output);
        JPanel actions = new JPanel(new GridLayout(1, 3, 4, 0));
        actions.add(refresh);
        actions.add(apply);
        actions.add(cancel);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(status, BorderLayout.NORTH);
        content.add(fields, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        Runnable scan =
                () -> {
                    String selectedMic = ((Choice) mic.getSelectedItem()).value,
                            selectedOutput = ((Choice) output.getSelectedItem()).value;
                    mic.setEnabled(false);
                    output.setEnabled(false);
                    apply.setEnabled(false);
                    refresh.setEnabled(false);
                    status.setText("Looking for audio devices…");
                    try {
                        scanner.execute(
                                () -> {
                                    try {
                                        List<String> inputs = AudioEngine.devices(true),
                                                outputs = AudioEngine.devices(false);
                                        SwingUtilities.invokeLater(
                                                () -> {
                                                    if (closed || dialog != window) {
                                                        return;
                                                    }
                                                    mic.setModel(choices(inputs, selectedMic));
                                                    output.setModel(
                                                            choices(outputs, selectedOutput));
                                                    mic.setEnabled(true);
                                                    output.setEnabled(true);
                                                    apply.setEnabled(true);
                                                    refresh.setEnabled(true);
                                                    status.setText(
                                                            "Default follows your operating system."
                                                                    + " Apply keeps your voice room.");
                                                });
                                    } catch (RuntimeException unavailable) {
                                        SwingUtilities.invokeLater(
                                                () -> {
                                                    if (closed || dialog != window) {
                                                        return;
                                                    }
                                                    refresh.setEnabled(true);
                                                    status.setText(
                                                            "Could not list devices. Check audio"
                                                                    + " settings, then Refresh.");
                                                });
                                    }
                                });
                    } catch (java.util.concurrent.RejectedExecutionException stopped) {
                        window.dispose();
                    }
                };
        refresh.addActionListener(e -> scan.run());
        apply.addActionListener(
                e -> {
                    if (!closed) {
                        save.accept(
                                ((Choice) mic.getSelectedItem()).value,
                                ((Choice) output.getSelectedItem()).value);
                    }
                    window.dispose();
                });
        cancel.addActionListener(e -> window.dispose());
        window.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent event) {
                        if (dialog == window) {
                            dialog = null;
                        }
                    }
                });
        window.setContentPane(content);
        window.pack();
        window.setLocationRelativeTo(parent);
        scan.run();
        window.setVisible(true);
    }

    @Override
    public void close() {
        closed = true;
        scanner.shutdownNow();
        SwingUtilities.invokeLater(
                () -> {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                });
    }
}
