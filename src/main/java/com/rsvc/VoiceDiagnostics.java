package com.rsvc;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.util.Filepath;

/** User-opened diagnostics. Disk writes use Filepath on a worker; no automatic recording/export. */
public final class VoiceDiagnostics implements AutoCloseable
{
    public final VoiceMetrics metrics = new VoiceMetrics();
    public final MicRecording recording = new MicRecording();
    private final ExecutorService files = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "RS VC test export"); t.setDaemon(true); return t; });
    private final AtomicBoolean saving = new AtomicBoolean();
    private volatile boolean closed;
    private JDialog dialog;
    private Timer timer;

    public void show(Component parent)
    {
        if (closed) return;
        if (dialog != null) { dialog.toFront(); return; }
        dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Voice statistics and mic test", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JTextArea stats = new JTextArea(19, 52); stats.setEditable(false);
        JLabel clip = new JLabel(); JLabel result = new JLabel("Exports stay on this computer. Choose a new filename.");
        JButton reset = new JButton("Reset stats"), csv = new JButton("Save stats CSV");
        JButton record = new JButton("Record my mic"), stop = new JButton("Stop recording");
        JButton wav = new JButton("Save mic WAV"), discard = new JButton("Discard clip");
        reset.addActionListener(e -> metrics.reset());
        csv.addActionListener(e -> save("csv", metrics.csv().getBytes(StandardCharsets.UTF_8), result));
        record.addActionListener(e -> {
            if (!closed && !recording.active() && recording.samples() == 0) {
                recording.start(); record.setEnabled(false); stop.setEnabled(true);
                clip.setText("RECORDING MY MIC — hold push-to-talk to capture");
            }
        });
        stop.addActionListener(e -> { recording.stop(); stop.setEnabled(false); });
        discard.addActionListener(e -> recording.discard());
        wav.addActionListener(e -> { if (!recording.active() && recording.samples() > 0) save("wav", recording.wav(), result); });
        JPanel controls = new JPanel(new GridLayout(3, 2, 4, 4));
        for (JButton button : new JButton[] { reset, csv, record, stop, wav, discard }) controls.add(button);
        JPanel bottom = new JPanel(new BorderLayout(0, 6)); bottom.add(clip, BorderLayout.NORTH);
        bottom.add(controls, BorderLayout.CENTER); bottom.add(result, BorderLayout.SOUTH);
        JTextArea notice = new JTextArea("Recording is OFF until you click Record my mic.\nOnly your raw mic during push-to-talk is captured (max 60 seconds).\nReleased-key gaps are omitted. Received voices are never recorded.\nUse headphones to prevent speakers leaking into your mic.");
        notice.setEditable(false); notice.setOpaque(false);
        JPanel content = new JPanel(new BorderLayout(6, 8)); content.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(notice, BorderLayout.NORTH); content.add(new JScrollPane(stats), BorderLayout.CENTER); content.add(bottom, BorderLayout.SOUTH);
        Runnable refresh = () -> {
            stats.setText(metrics.summary());
            clip.setText(String.format(java.util.Locale.ROOT, "%s — %.2f / 60 seconds captured", recording.active() ? "RECORDING MY MIC" : "Recording stopped", recording.samples() / 48000.0));
            record.setEnabled(!recording.active() && recording.samples() == 0 && !saving.get()); stop.setEnabled(recording.active());
            wav.setEnabled(!recording.active() && recording.samples() > 0 && !saving.get()); csv.setEnabled(!saving.get());
        };
        timer = new Timer(500, e -> refresh.run()); refresh.run(); timer.start();
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) {
            recording.stop(); if (timer != null) timer.stop(); timer = null; dialog = null;
        } });
        dialog.setContentPane(content); dialog.pack(); dialog.setLocationRelativeTo(parent); dialog.setVisible(true);
    }

    private void save(String extension, byte[] bytes, JLabel result)
    {
        if (closed || !saving.compareAndSet(false, true)) { java.util.Arrays.fill(bytes, (byte) 0); return; }
        List<Filepath> selected;
        try {
            selected = new Filepath.Chooser().setIsSave().setAcceptsFiles()
                .setDialogTitle("Save voice test " + extension).setDefaultExtension(extension)
                .addExtensionFilter(extension.toUpperCase(java.util.Locale.ROOT), extension)
                .setFileName("voice-test-" + System.currentTimeMillis() + "." + extension).showDialog(dialog);
        } catch (RuntimeException unavailable) {
            java.util.Arrays.fill(bytes, (byte) 0); saving.set(false); result.setText("File chooser unavailable."); return;
        }
        if (selected == null || selected.isEmpty() || closed) { java.util.Arrays.fill(bytes, (byte) 0); saving.set(false); return; }
        result.setText("Saving…");
        try { files.execute(() -> {
            String message;
            try { selected.get(0).write(bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); message = "Saved " + selected.get(0).getFileName(); }
            catch (java.io.IOException | RuntimeException error) { message = "Could not save; choose a new filename and writable folder."; }
            finally { java.util.Arrays.fill(bytes, (byte) 0); saving.set(false); }
            String completed = message; SwingUtilities.invokeLater(() -> result.setText(completed));
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) { java.util.Arrays.fill(bytes, (byte) 0); saving.set(false); }
    }

    @Override public void close()
    {
        closed = true; recording.discard(); files.shutdownNow();
        SwingUtilities.invokeLater(() -> { if (timer != null) timer.stop(); if (dialog != null) dialog.dispose(); });
    }
}
