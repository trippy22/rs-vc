import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.concentus.OpusApplication;
import org.concentus.OpusBandwidth;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusMode;
import org.concentus.OpusSignal;

/** Development-only offline experiment; not part of the RuneLite plugin. */
public final class CodecAudit {
    private static final int RATE = 48000, FRAME = 960, LIMIT = 400;

    public static void main(String[] args) throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(args[0]));
        short[] fixture = new short[raw.length / 2];
        for (int i = 0; i < fixture.length; i++) fixture[i] = (short) ((raw[2 * i] & 255) | (raw[2 * i + 1] << 8));
        System.out.println("profile,target_kbps,fixture_kbps,silence_kbps,max_bytes,silk_frames,hybrid_frames,celt_frames,tiny_silence_frames,sha256");
        run("baseline24", fixture, 24000, false, false, false, false);
        run("voice20", fixture, 20000, true, false, false, false);
        run("voice16", fixture, 16000, true, false, false, false);
        run("voice16_dtx", fixture, 16000, true, true, false, false);
        run("voice16_wide_dtx", fixture, 16000, true, true, true, false);
        run("voice16_silk_dtx", fixture, 16000, true, true, true, true);
        reverseCompatibility(fixture);
    }

    private static void run(String name, short[] fixture, int bitrate, boolean voice, boolean dtx, boolean wide, boolean silk) throws Exception {
        OpusEncoder encoder = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(bitrate); encoder.setComplexity(4);
        if (voice) encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        encoder.setUseDTX(dtx);
        if (wide) encoder.setMaxBandwidth(OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND);
        if (silk) encoder.setForceMode(OpusMode.MODE_SILK_ONLY);
        OpusDecoder decoder = new OpusDecoder(RATE, 1);
        io.github.jaredmdobson.concentus.OpusDecoder published = new io.github.jaredmdobson.concentus.OpusDecoder(RATE, 1);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int fixtureFrames = fixture.length / FRAME, silenceFrames = 300;
        long fixtureBytes = 0, silenceBytes = 0, energy = 0;
        int max = 0, tiny = 0;
        int[] modes = new int[3];
        for (int frame = 0; frame < fixtureFrames + silenceFrames; frame++) {
            boolean silent = frame >= fixtureFrames;
            short[] input = silent ? new short[FRAME] : Arrays.copyOfRange(fixture, frame * FRAME, (frame + 1) * FRAME);
            byte[] packet = new byte[LIMIT];
            int size = encoder.encode(input, 0, FRAME, packet, 0, LIMIT);
            if (size < 1 || size > LIMIT || (packet[0] & 4) != 0) throw new AssertionError("Invalid/non-mono packet");
            max = Math.max(max, size);
            if (silent) { silenceBytes += size; if (size <= 2) tiny++; } else fixtureBytes += size;
            int config = (packet[0] & 255) >> 3;
            modes[config < 12 ? 0 : config < 16 ? 1 : 2]++;
            digest.update((byte) (size >> 8)); digest.update((byte) size); digest.update(packet, 0, size);
            short[] decoded = new short[FRAME], external = new short[FRAME];
            if (decoder.decode(packet, 0, size, decoded, 0, FRAME, false) != FRAME ||
                published.decode(packet, 0, size, external, 0, FRAME, false) != FRAME) throw new AssertionError("Frame duration");
            if (!Arrays.equals(decoded, external)) throw new AssertionError("Published decoder mismatch: " + name);
            for (short sample : decoded) {
                energy += (long) sample * sample;
                digest.update((byte) sample); digest.update((byte) (sample >> 8));
            }
        }
        if (energy < 1_000_000_000L) throw new AssertionError("Unexpected silent decode");
        System.out.printf(Locale.ROOT, "%s,%d,%.3f,%.3f,%d,%d,%d,%d,%d,%s%n", name, bitrate / 1000,
            fixtureBytes * 8.0 / (fixtureFrames * 20), silenceBytes * 8.0 / (silenceFrames * 20),
            max, modes[0], modes[1], modes[2], tiny, hex(digest.digest()));
    }

    private static void reverseCompatibility(short[] fixture) throws Exception {
        io.github.jaredmdobson.concentus.OpusEncoder encoder = new io.github.jaredmdobson.concentus.OpusEncoder(RATE, 1,
            io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(24000); encoder.setComplexity(4);
        OpusDecoder decoder = new OpusDecoder(RATE, 1);
        io.github.jaredmdobson.concentus.OpusDecoder published = new io.github.jaredmdobson.concentus.OpusDecoder(RATE, 1);
        for (int frame = 0; frame < fixture.length / FRAME; frame++) {
            byte[] packet = new byte[LIMIT];
            int size = encoder.encode(fixture, frame * FRAME, FRAME, packet, 0, LIMIT);
            short[] decoded = new short[FRAME], external = new short[FRAME];
            if (decoder.decode(packet, 0, size, decoded, 0, FRAME, false) != FRAME ||
                published.decode(packet, 0, size, external, 0, FRAME, false) != FRAME ||
                !Arrays.equals(decoded, external)) throw new AssertionError("Reverse interoperability failed");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
