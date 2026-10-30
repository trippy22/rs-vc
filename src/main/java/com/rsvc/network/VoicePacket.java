package com.rsvc.network;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Mono, 48 kHz Opus packets. Senders use 20 ms; receivers also play 40 ms. Sequences count 20 ms
 * frames, including omitted ones, and a packet carries its first frame's number. Integers use
 * network byte order.
 */
public final class VoicePacket {
    public static final int MAX_OPUS = 400;
    public final long speaker;
    public final long sequence;
    public final byte[] opus;

    private VoicePacket(long speaker, long sequence, byte[] opus) {
        this.speaker = speaker;
        this.sequence = sequence;
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

    /** Relay to client: speaker ID, sequence, then the speaker's Opus bytes. */
    public static VoicePacket downstream(byte[] bytes) {
        if (bytes.length < 9 || bytes.length > MAX_OPUS + 8) {
            throw new IllegalArgumentException("Invalid voice packet");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long speaker = Integer.toUnsignedLong(buf.getInt());
        long sequence = Integer.toUnsignedLong(buf.getInt());
        if (speaker == 0) {
            throw new IllegalArgumentException("Invalid speaker");
        }
        return new VoicePacket(speaker, sequence, Arrays.copyOfRange(bytes, 8, bytes.length));
    }
}
