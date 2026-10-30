package com.rsvc.audio;

import com.rsvc.codec.VoiceDecoder;
import com.rsvc.codec.VoiceEncoder;
import com.rsvc.network.VoicePacket;

import java.util.Arrays;

public final class OpusCodec {
    public static final int RATE = 48000;
    public static final int SAMPLES = 960;
    public static final int BITRATE = 16000;

    private OpusCodec() {}

    public static VoiceEncoder encoder() {
        return encoder(true);
    }

    public static VoiceEncoder encoder(boolean suppressSilence) {
        return new VoiceEncoder(BITRATE, suppressSilence);
    }

    public static VoiceDecoder decoder() {
        return new VoiceDecoder();
    }

    // Opus DTX emits a TOC-only/very small packet when there is nothing to transmit.
    // Do not apply a PCM volume gate: quiet speech must remain the encoder's decision.
    public static boolean suppress(byte[] packet) {
        return packet.length <= 2;
    }

    public static byte[] encode(VoiceEncoder encoder, short[] pcm) {
        byte[] out = new byte[VoicePacket.MAX_OPUS];
        int size = encoder.encode(pcm, out);
        return Arrays.copyOf(out, size);
    }
}
