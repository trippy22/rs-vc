package com.rsvc;

import java.util.Random;
import org.concentus.OpusApplication;
import org.concentus.OpusBandwidth;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusMode;
import org.concentus.OpusPacketInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonoCodecTest
{
    private static short[] voice(int frame)
    {
        short[] samples = new short[960];
        Random noise = new Random(frame);
        for (int i = 0; i < samples.length; i++)
        {
            double t = (frame * 960 + i) / 48000.0;
            samples[i] = (short) (7000 * Math.sin(2 * Math.PI * 140 * t)
                + 3500 * Math.sin(2 * Math.PI * 700 * t)
                + 2000 * Math.sin(2 * Math.PI * 2100 * t) + noise.nextInt(1500) - 750);
        }
        return samples;
    }

    @Test public void productionProfileEmitsLowBitrateMonoTwentyMillisecondPackets() throws Exception
    {
        OpusEncoder encoder = OpusCodec.encoder();
        OpusDecoder decoder = OpusCodec.decoder();
        long bytes = 0, energy = 0;
        int frames = 200;
        for (int frame = 0; frame < frames; frame++)
        {
            byte[] packet = OpusCodec.encode(encoder, voice(frame));
            bytes += packet.length;
            assertEquals(1, OpusPacketInfo.getNumEncodedChannels(packet, 0));
            assertEquals(960, OpusPacketInfo.getNumSamples(packet, 0, packet.length, 48000));
            short[] decoded = new short[960];
            assertEquals(960, decoder.decode(packet, 0, packet.length, decoded, 0, 960, false));
            for (short value : decoded) energy += (long) value * value;
        }
        double measuredBitrate = bytes * 8.0 / (frames * 0.020);
        assertTrue("Voice fixture must fit below 20 kbps: " + measuredBitrate, measuredBitrate < 20000);
        assertTrue("Decoded audio must be audible", energy > 1_000_000_000L);
    }

    @Test public void fullDecoderRetainsSilkHybridCeltAndModeTransitions() throws Exception
    {
        OpusEncoder sender = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        sender.setComplexity(4);
        OpusDecoder receiver = OpusCodec.decoder();
        OpusMode[] modes = { OpusMode.MODE_SILK_ONLY, OpusMode.MODE_HYBRID, OpusMode.MODE_CELT_ONLY, OpusMode.MODE_SILK_ONLY };
        int frame = 0;
        for (OpusMode mode : modes)
        {
            sender.setForceMode(mode);
            sender.setBandwidth(mode == OpusMode.MODE_SILK_ONLY ? OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND : OpusBandwidth.OPUS_BANDWIDTH_FULLBAND);
            sender.setBitrate(mode == OpusMode.MODE_SILK_ONLY ? 16000 : mode == OpusMode.MODE_HYBRID ? 32000 : 64000);
            long energy = 0;
            for (int n = 0; n < 20; n++)
            {
                byte[] packet = new byte[400];
                int size = sender.encode(voice(frame++), 0, 960, packet, 0, packet.length);
                if (n == 19) assertEquals(mode, OpusPacketInfo.getEncoderMode(packet, 0));
                short[] decoded = new short[960];
                assertEquals(960, receiver.decode(packet, 0, size, decoded, 0, 960, false));
                for (short value : decoded) energy += (long) value * value;
            }
            assertTrue("Mode must decode audible audio: " + mode, energy > 1_000_000_000L);
        }
    }
}
