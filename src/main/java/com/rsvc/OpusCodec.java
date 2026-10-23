package com.rsvc;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.concentus.OpusSignal;
import java.util.Arrays;

public final class OpusCodec
{
    public static final int RATE = 48000;
    public static final int SAMPLES = 960;
    public static final int BITRATE = 16000;

    private OpusCodec() {}

    public static OpusEncoder encoder() throws OpusException
    {
        return encoder(true);
    }

    public static OpusEncoder encoder(boolean suppressSilence) throws OpusException
    {
        OpusEncoder encoder = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(BITRATE);
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        encoder.setComplexity(4);
        encoder.setUseDTX(suppressSilence);
        return encoder;
    }

    public static OpusDecoder decoder() throws OpusException { return new OpusDecoder(RATE, 1); }

    // Opus DTX emits a TOC-only/very small packet when there is nothing to transmit.
    // Do not apply a PCM volume gate: quiet speech must remain the encoder's decision.
    public static boolean suppress(byte[] packet) { return packet.length <= 2; }

    public static byte[] encode(OpusEncoder encoder, short[] pcm) throws OpusException
    {
        byte[] out = new byte[VoicePacket.MAX_OPUS];
        int size = encoder.encode(pcm, 0, SAMPLES, out, 0, out.length);
        return Arrays.copyOf(out, size);
    }
}
