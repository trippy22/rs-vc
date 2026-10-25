package com.rsvc.audio;

import com.rsvc.VoiceObserver;
import com.rsvc.network.VoicePacket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** Audio devices and codec run on dedicated threads, never the game or Swing thread. */
public final class AudioEngine implements AutoCloseable {
    static final AudioFormat FORMAT = new AudioFormat(48000, 16, 1, true, false);
    private final PlaybackBuffer playback = new PlaybackBuffer();
    private final SpeechActivity microphoneActivity = new SpeechActivity();
    private final ExecutorService input = executor("RS VC capture");
    private final ExecutorService output = executor("RS VC playback");
    private final ExecutorService devices = executor("RS VC device cleanup");
    private final AtomicLong captureEpoch = new AtomicLong();
    private final Consumer<byte[]> transmit;
    private final BooleanSupplier audience;
    private final Consumer<String> error;
    private final IntSupplier volume;
    private final String microphone;
    private final boolean suppressSilence;
    private volatile VoiceObserver observer;
    private volatile TargetDataLine capture;
    private volatile SourceDataLine speaker;
    private volatile boolean closed;
    private volatile boolean deaf;
    private long sequence;

    public AudioEngine(
            String microphone,
            String speakers,
            IntSupplier volume,
            Consumer<byte[]> transmit,
            BooleanSupplier audience,
            Consumer<String> error,
            boolean suppressSilence,
            VoiceObserver observer) {
        this.microphone = microphone;
        this.volume = volume;
        this.transmit = transmit;
        this.audience = audience;
        this.error = error;
        this.suppressSilence = suppressSilence;
        this.observer = observer;
        output.execute(() -> play(speakers));
    }

    public void observer(VoiceObserver next) {
        observer = next;
    }

    private static ExecutorService executor(String name) {
        return Executors.newSingleThreadExecutor(
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                });
    }

    public static List<String> devices(boolean input) {
        List<String> names = new ArrayList<>();
        DataLine.Info info =
                new DataLine.Info(input ? TargetDataLine.class : SourceDataLine.class, FORMAT);
        for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mixer).isLineSupported(info)) {
                names.add(mixer.getName());
            }
        }
        return names;
    }

    static Line line(boolean input, String name) throws Exception {
        DataLine.Info info =
                new DataLine.Info(input ? TargetDataLine.class : SourceDataLine.class, FORMAT);
        if (name.trim().isEmpty()) {
            return AudioSystem.getLine(info);
        }
        for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
            if (mixer.getName().equals(name) && AudioSystem.getMixer(mixer).isLineSupported(info)) {
                return AudioSystem.getMixer(mixer).getLine(info);
            }
        }
        throw new IllegalArgumentException("Audio device unavailable: " + name);
    }

    public void startCapture() {
        if (closed) {
            return;
        }
        long epoch = captureEpoch.incrementAndGet();
        try {
            input.execute(() -> capture(epoch));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            /* Shutdown raced a key press. */
        }
    }

    public void stopCapture() {
        captureEpoch.incrementAndGet();
        microphoneActivity.clear();
        observer.idle();
        TargetDataLine current = capture;
        if (current != null) {
            deviceTask(current::close);
        } // Unblocks read without blocking the game thread.
    }

    private void deviceTask(Runnable task) {
        try {
            devices.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            /* Already closing. */
        }
    }

    private void capture(long epoch) {
        TargetDataLine device = null;
        try {
            if (closed || epoch != captureEpoch.get()) {
                return;
            }
            device = (TargetDataLine) line(true, microphone);
            device.open(FORMAT, OpusCodec.SAMPLES * 2 * 4);
            capture = device;
            if (closed || epoch != captureEpoch.get()) {
                return;
            }
            device.start();
            CaptureProcessor processor = new CaptureProcessor(microphoneActivity, suppressSilence);
            byte[] bytes = new byte[OpusCodec.SAMPLES * 2];
            while (!closed && epoch == captureEpoch.get()) {
                int offset = 0;
                while (offset < bytes.length && !closed && epoch == captureEpoch.get()) {
                    int read = device.read(bytes, offset, bytes.length - offset);
                    if (read <= 0) {
                        return;
                    }
                    offset += read;
                }
                if (closed || epoch != captureEpoch.get()) {
                    return;
                }
                short[] pcm = new short[OpusCodec.SAMPLES];
                for (int i = 0; i < pcm.length; i++) {
                    pcm[i] = (short) ((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8));
                }
                byte[] encoded =
                        processor.process(
                                pcm,
                                audience.getAsBoolean(),
                                System.nanoTime() / 1_000_000,
                                observer);
                long frameSequence =
                        sequence++; // Includes omitted frames, so receivers can identify gaps.
                if (closed || epoch != captureEpoch.get()) {
                    return;
                }
                if (encoded != null
                        && audience.getAsBoolean()
                        && !closed
                        && epoch == captureEpoch.get()) {
                    transmit.accept(VoicePacket.upstream(frameSequence, encoded));
                }
            }
        } catch (Exception e) {
            if (!closed && epoch == captureEpoch.get()) {
                error.accept("Microphone unavailable: " + e.getMessage());
            }
        } finally {
            observer.idle();
            if (device != null) {
                device.close();
            }
            if (capture == device) {
                capture = null;
            }
        }
    }

    private void play(String speakers) {
        SourceDataLine device = null;
        try {
            device = (SourceDataLine) line(false, speakers);
            device.open(FORMAT, OpusCodec.SAMPLES * 2 * 4);
            speaker = device;
            if (closed) {
                return;
            }
            device.start();
            while (!closed) {
                short[] pcm =
                        deaf
                                ? new short[OpusCodec.SAMPLES]
                                : playback.mix(System.nanoTime() / 1_000_000, volume.getAsInt());
                byte[] bytes = new byte[pcm.length * 2];
                for (int i = 0; i < pcm.length; i++) {
                    bytes[2 * i] = (byte) pcm[i];
                    bytes[2 * i + 1] = (byte) (pcm[i] >> 8);
                }
                // The device's bounded buffer paces playback at 20 ms per frame.
                int offset = 0;
                while (!closed && offset < bytes.length) {
                    int written = device.write(bytes, offset, bytes.length - offset);
                    if (written <= 0) {
                        return;
                    }
                    offset += written;
                }
            }
        } catch (Exception e) {
            if (!closed) {
                error.accept("Speakers unavailable: " + e.getMessage());
            }
        } finally {
            if (device != null) {
                device.close();
            }
            if (speaker == device) {
                speaker = null;
            }
        }
    }

    public void receive(VoicePacket packet) {
        if (closed || deaf) {
            return;
        }
        try {
            playback.offer(packet, System.nanoTime() / 1_000_000);
        } catch (Exception ignored) {
            playback.remove(packet.speaker);
        }
    }

    public void deafen(boolean value) {
        deaf = value;
        clearPlayback();
    }

    public void clearPlayback() {
        playback.clear();
        SourceDataLine current = speaker;
        if (current != null) {
            deviceTask(current::flush);
        }
    }

    public void block(long id) {
        playback.remove(id);
    }

    public void volumes(java.util.Map<Long, Integer> values) {
        playback.volumes(values);
    }

    public boolean microphoneSpeaking() {
        return !closed && microphoneActivity.active(System.nanoTime() / 1_000_000);
    }

    public java.util.Set<Long> speaking() {
        return closed || deaf || volume.getAsInt() <= 0
                ? java.util.Collections.emptySet()
                : playback.speaking(System.nanoTime() / 1_000_000);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        microphoneActivity.clear();
        captureEpoch.incrementAndGet();
        playback.clear();
        input.shutdownNow();
        output.shutdownNow();
        // Let queued device cleanup finish off-thread before shutting down its executor.
        // Lines still opening are closed by capture/play's finally blocks.
        deviceTask(
                () -> {
                    try {
                        TargetDataLine currentCapture = capture;
                        try {
                            if (currentCapture != null) {
                                currentCapture.close();
                            }
                        } finally {
                            SourceDataLine currentSpeaker = speaker;
                            if (currentSpeaker != null) {
                                currentSpeaker.close();
                            }
                        }
                    } finally {
                        devices.shutdownNow();
                    }
                });
    }
}
