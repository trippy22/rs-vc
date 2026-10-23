package com.rsvc;

import java.nio.ByteBuffer;
import org.concentus.OpusEncoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class SilenceSuppressionTest
{
    @Test public void silenceDropsPacketsAndSpeechResumesThroughProductionPlayback() throws Exception
    {
        OpusEncoder encoder = OpusCodec.encoder(); PlaybackBuffer playback = new PlaybackBuffer();
        int suppressed = 0; long resumedEnergy = 0;
        for (int frame = 0; frame < 500; frame++) {
            short[] pcm = new short[960];
            if (frame < 100 || frame >= 400) for (int i = 0; i < pcm.length; i++)
                pcm[i] = (short) (10000 * Math.sin(2 * Math.PI * 440 * (frame * 960 + i) / 48000));
            byte[] opus = OpusCodec.encode(encoder, pcm);
            if (OpusCodec.suppress(opus)) suppressed++;
            else playback.offer(packet(frame, opus), 1000 + frame * 20);
            short[] out = playback.mix(1000 + frame * 20, 100);
            if (frame >= 400) for (short sample : out) resumedEnergy += (long) sample * sample;
        }
        assertTrue("Most silent packets must be omitted: " + suppressed, suppressed > 240);
        assertTrue("Speech must resume audibly after DTX", resumedEnergy > 1_000_000_000L);
        assertArrayEquals(new short[960], playback.mix(12000, 100));
    }

    @Test public void sequenceGapsDiscardQueuedOldAudioAndWrapStillWorks() throws Exception
    {
        PlaybackBuffer playback = new PlaybackBuffer(); OpusEncoder e = OpusCodec.encoder(false);
        byte[] opus = OpusCodec.encode(e, new short[960]);
        playback.offer(packet(0xfffffffeL, opus), 1000);
        playback.offer(packet(0xffffffffL, opus), 1020);
        playback.offer(packet(0, opus), 1040); assertEquals(1, playback.speakerCount());
        playback.offer(packet(50, opus), 1060);
        assertArrayEquals("A fresh gap must re-prime instead of playing old queued packets", new short[960], playback.mix(1060, 100));
        playback.offer(packet(49, opus), 1070); // stale packets do not rewind the stream
        playback.mix(1100, 100); assertEquals(1, playback.speakerCount());
    }

    private static VoicePacket packet(long sequence, byte[] opus)
    {
        return VoicePacket.downstream(ByteBuffer.allocate(10 + opus.length).put((byte) 1).putInt(1)
            .putInt((int) sequence).put((byte) 255).put(opus).array());
    }
}
