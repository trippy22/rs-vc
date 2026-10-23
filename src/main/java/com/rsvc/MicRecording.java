package com.rsvc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Explicit, bounded, local raw-mic test clip. Never accepts decoded/received audio. */
public final class MicRecording
{
    static final int MAX_SAMPLES = 48000 * 60;
    private byte[] pcm;
    private int used;
    private boolean recording;

    public synchronized void start()
    {
        if (recording) return;
        if (used != 0) throw new IllegalStateException("Save/discard the previous clip first");
        pcm = new byte[MAX_SAMPLES * 2]; recording = true;
    }
    public synchronized void append(short[] samples)
    {
        if (!recording) return;
        for (short sample : samples) {
            if (used == pcm.length) { recording = false; break; }
            pcm[used++] = (byte) sample; pcm[used++] = (byte) (sample >> 8);
        }
        if (used == pcm.length) recording = false;
    }
    public synchronized void stop() { recording = false; }
    public synchronized boolean active() { return recording; }
    public synchronized int samples() { return used / 2; }
    public synchronized void discard() { recording = false; if (pcm != null) java.util.Arrays.fill(pcm, (byte) 0); pcm = null; used = 0; }
    public synchronized byte[] wav()
    {
        if (recording || used == 0) throw new IllegalStateException("Stop and capture some microphone audio first");
        ByteBuffer out = ByteBuffer.allocate(44 + used).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46464952).putInt(36 + used).putInt(0x45564157).putInt(0x20746d66).putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(48000).putInt(96000).putShort((short) 2).putShort((short) 16);
        out.putInt(0x61746164).putInt(used).put(pcm, 0, used); return out.array();
    }
}
