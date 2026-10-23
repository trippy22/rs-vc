package com.rsvc;

import java.util.Locale;
import java.util.function.LongSupplier;

/** Bounded counters only; no names, channel secrets, positions or audio retained. */
public final class VoiceMetrics
{
    private final LongSupplier clock;
    private final long[][] counts = new long[4][3]; // tx audio/control, rx audio/control: packets, bytes, estimated WS bytes
    private final long[] durations = new long[4]; // disconnected, idle, encoded audio, DTX silence
    private final long[][] stateBytes = new long[4][4]; // mode x tx audio/control, rx audio/control
    private long since;
    private boolean connected;
    private int mode;
    private long frames, suppressed, suppressedBytes;
    private String profile = "DTX + suppression";

    public VoiceMetrics() { this(System::nanoTime); }
    VoiceMetrics(LongSupplier clock) { this.clock = clock; since = clock.getAsLong(); }
    private void accrue() { long now = clock.getAsLong(); durations[connected ? mode : 0] += Math.max(0, now - since); since = now; }
    public synchronized void connected(boolean value) { accrue(); connected = value; mode = 1; }
    public synchronized void idle() { accrue(); mode = 1; }
    public synchronized void profile(boolean baseline)
    {
        String next = baseline ? "Baseline: no DTX" : "DTX + suppression";
        if (!next.equals(profile)) { reset(); profile = next; }
    }
    public synchronized void encoded(int bytes, boolean dropped)
    {
        accrue(); mode = dropped ? 3 : 2; frames++;
        if (dropped) { suppressed++; suppressedBytes += bytes + 5; }
    }
    public synchronized void message(boolean outgoing, boolean audio, int bytes)
    {
        int row = (outgoing ? 0 : 2) + (audio ? 0 : 1);
        counts[row][0]++; counts[row][1] += bytes; counts[row][2] += framedBytes(bytes, outgoing);
        stateBytes[connected ? mode : 0][row] += bytes;
    }
    public static long framedBytes(int bytes, boolean outgoing)
    {
        return (long) bytes + (bytes <= 125 ? 2 : bytes <= 65535 ? 4 : 10) + (outgoing ? 4 : 0);
    }
    public synchronized void reset()
    {
        for (long[] row : counts) java.util.Arrays.fill(row, 0);
        for (long[] row : stateBytes) java.util.Arrays.fill(row, 0);
        java.util.Arrays.fill(durations, 0); frames = suppressed = suppressedBytes = 0; since = clock.getAsLong();
    }
    public synchronized String summary()
    {
        accrue();
        return String.format(Locale.ROOT,
            "%s%nState: %s%nIdle %.1fs | Audio %.1fs | DTX silence %.1fs%nSent: %.2f KiB audio + %.2f KiB control%nReceived: %.2f KiB audio + %.2f KiB control%nCaptured frames: %d | Suppressed: %d (%.2fs)%nSuppression saved: %d message bytes + %d frames%n%nAudio = encoded activity, including warm-up/noise.%nIdle = mic not capturing; incoming voices still count.%nSent bytes = accepted by OkHttp, not delivery proof.%nWS estimates exclude TLS/TCP/IP, ACKs, retries,%nhandshake and WebSocket ping/pong traffic.",
            profile, !connected ? "Disconnected" : mode == 3 ? "DTX silence" : mode == 2 ? "Audio" : "Idle",
            durations[1] / 1e9, durations[2] / 1e9, durations[3] / 1e9,
            counts[0][1] / 1024.0, counts[1][1] / 1024.0, counts[2][1] / 1024.0, counts[3][1] / 1024.0,
            frames, suppressed, suppressed * .02, suppressedBytes, suppressed);
    }
    public synchronized String csv()
    {
        accrue();
        StringBuilder out = new StringBuilder("metric,value,unit\nprofile,").append(profile).append(",label\n");
        String[] modes = { "disconnected", "idle", "audio", "dtx_silence" };
        for (int i = 0; i < modes.length; i++) out.append(modes[i]).append(',').append(durations[i] / 1e9).append(",seconds\n");
        String[] types = { "tx_audio", "tx_control", "rx_audio", "rx_control" };
        double connectedSeconds = (durations[1] + durations[2] + durations[3]) / 1e9;
        for (int i = 0; i < types.length; i++) {
            out.append(types[i]).append("_messages,").append(counts[i][0]).append(",messages\n");
            out.append(types[i]).append("_bytes,").append(counts[i][1]).append(",bytes\n");
            out.append(types[i]).append("_estimated_ws_bytes,").append(counts[i][2]).append(",bytes\n");
            out.append(types[i]).append("_average_kbps,").append(connectedSeconds == 0 ? 0 : counts[i][1] * 8 / connectedSeconds / 1000).append(",kbps\n");
        }
        for (int mode = 0; mode < modes.length; mode++) for (int type = 0; type < types.length; type++)
            out.append(modes[mode]).append('_').append(types[type]).append("_bytes,").append(stateBytes[mode][type]).append(",bytes\n");
        return out.append("captured_frames,").append(frames).append(",frames\nsuppressed_frames,").append(suppressed)
            .append(",frames\nsuppressed_message_bytes,").append(suppressedBytes)
            .append(",bytes\nnetwork_overhead_included,false,boolean\n").toString();
    }
}
