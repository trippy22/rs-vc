package com.rsvc.network;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Fixed mono, 48 kHz, 20 ms Opus packets. Integers use network byte order. */
public final class VoicePacket {
    public static final int MAX_OPUS = 400;
    public final long speaker;
    public final long sequence;
    public final float gain;
    public final byte[] opus;

    private VoicePacket(long speaker, long sequence, float gain, byte[] opus) {
        this.speaker = speaker;
        this.sequence = sequence;
        this.gain = gain;
        this.opus = opus;
    }

    public static byte[] upstream(long sequence, byte[] opus) {
        if (opus.length == 0 || opus.length > MAX_OPUS) {
            throw new IllegalArgumentException("Invalid Opus length");
        }
        return ByteBuffer.allocate(5 + opus.length)
                .put((byte) 1)
                .putInt((int) sequence)
                .put(opus)
                .array();
    }

    public static VoicePacket downstream(byte[] bytes) {
        if (bytes.length < 11 || bytes.length > MAX_OPUS + 10 || bytes[0] != 1) {
            throw new IllegalArgumentException("Invalid voice packet");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.get();
        long speaker = Integer.toUnsignedLong(buf.getInt());
        long sequence = Integer.toUnsignedLong(buf.getInt());
        float gain = Byte.toUnsignedInt(buf.get()) / 255f;
        if (speaker == 0) {
            throw new IllegalArgumentException("Invalid speaker");
        }
        return new VoicePacket(
                speaker, sequence, gain, Arrays.copyOfRange(bytes, 10, bytes.length));
    }
}
