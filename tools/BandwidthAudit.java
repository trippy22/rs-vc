import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusSignal;

/** Offline byte counts using an existing PCM fixture; never opens a mic or records audio. */
public final class BandwidthAudit
{
    private static final int FRAME = 960;

    public static void main(String[] args) throws Exception
    {
        byte[] raw = Files.readAllBytes(Paths.get(args[0]));
        if (args[0].toLowerCase(Locale.ROOT).endsWith(".wav")) {
            try (javax.sound.sampled.AudioInputStream audio = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(raw))) {
                javax.sound.sampled.AudioFormat f = audio.getFormat();
                if (!f.getEncoding().equals(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED) || f.getSampleRate() != 48000 ||
                    f.getChannels() != 1 || f.getSampleSizeInBits() != 16 || f.isBigEndian())
                    throw new IllegalArgumentException("Use a 48kHz 16-bit mono PCM WAV, as exported by Stats / test");
                raw = audio.readAllBytes();
            }
        }
        short[] pcm = new short[raw.length / 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((raw[i * 2] & 255) | (raw[i * 2 + 1] << 8));
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(raw)) hash.append(String.format("%02x", b & 255));
        System.err.println("Fixture SHA256: " + hash + "; frames: " + pcm.length / FRAME + "; appended silence: 6 seconds");
        if (pcm.length < FRAME) throw new IllegalArgumentException("Capture at least one 20ms frame");
        System.out.println("profile,fixture_payload_kbps,silence_payload_kbps,fixture_payload_MB_per_hour,fixture_WS_upload_kbps,silence_WS_upload_kbps,suppressed_fixture_frames,suppressed_silence_frames");
        run("opus24_complexity4", pcm, 24000, 4, false, false);
        run("opus16_complexity4_baseline", pcm, 16000, 4, false, false);
        run("opus12_complexity4", pcm, 12000, 4, false, false);
        run("opus16_complexity1", pcm, 16000, 1, false, false);
        run("opus16_complexity4_DTX_only", pcm, 16000, 4, true, false);
        run("opus16_DTX_suppression_current", pcm, 16000, 4, true, true);
        // Arithmetic baseline: 48 kHz * 16 bits * one channel, no codec or silence suppression.
        report("raw_PCM48k_calculated", (long) (pcm.length / FRAME) * FRAME * 2, 300L * FRAME * 2,
            (long) (pcm.length / FRAME) * (FRAME * 2 + 5 + 8), 300L * (FRAME * 2 + 5 + 8), pcm.length / FRAME, 0, 0);
    }

    private static void run(String profile, short[] pcm, int bitrate, int complexity, boolean dtx, boolean suppress) throws Exception
    {
        OpusEncoder encoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(bitrate); encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        encoder.setComplexity(complexity); encoder.setUseDTX(dtx);
        int frames = pcm.length / FRAME;
        long voice = 0, silence = 0, voiceWire = 0, silenceWire = 0;
        int droppedVoice = 0, droppedSilence = 0;
        for (int i = 0; i < frames + 300; i++) {
            short[] input = i < frames ? Arrays.copyOfRange(pcm, i * FRAME, (i + 1) * FRAME) : new short[FRAME];
            byte[] output = new byte[400];
            int size = encoder.encode(input, 0, FRAME, output, 0, output.length);
            if (size < 1 || size > output.length) throw new AssertionError("Invalid packet size");
            if (suppress && com.rsvc.OpusCodec.suppress(Arrays.copyOf(output, size))) {
                if (i < frames) droppedVoice++; else droppedSilence++; continue;
            }
            // Current 5-byte app header; one masked, unfragmented WS frame per Opus packet.
            int framed = size + 5 + (size + 5 <= 125 ? 6 : 8);
            if (i < frames) { voice += size; voiceWire += framed; }
            else { silence += size; silenceWire += framed; }
        }
        report(profile, voice, silence, voiceWire, silenceWire, frames, droppedVoice, droppedSilence);
    }

    private static void report(String name, long voice, long silence, long voiceWire, long silenceWire, int frames, int droppedVoice, int droppedSilence)
    {
        double seconds = frames * 0.02;
        System.out.printf(Locale.ROOT, "%s,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d%n", name,
            voice * 8 / seconds / 1000, silence * 8 / 6.0 / 1000, voice / seconds * 3600 / 1_000_000,
            voiceWire * 8 / seconds / 1000, silenceWire * 8 / 6.0 / 1000, droppedVoice, droppedSilence);
    }
}
