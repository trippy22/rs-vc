package com.rsvc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceDiagnosticsTest
{
    @Test public void countersSeparateStatesAudioControlAndWireEstimates()
    {
        AtomicLong clock = new AtomicLong(); VoiceMetrics m = new VoiceMetrics(clock::get);
        m.connected(true); clock.addAndGet(2_000_000_000L);
        m.message(true, false, 100); m.encoded(40, false); m.message(true, true, 45);
        clock.addAndGet(1_000_000_000L); m.encoded(1, true);
        clock.addAndGet(3_000_000_000L); m.message(false, true, 50); m.connected(false);
        clock.addAndGet(4_000_000_000L);
        String csv = m.csv();
        for (String row : new String[] { "idle,2.0,seconds", "audio,1.0,seconds", "dtx_silence,3.0,seconds",
            "disconnected,4.0,seconds", "tx_audio_bytes,45,bytes", "tx_control_bytes,100,bytes",
            "rx_audio_bytes,50,bytes", "suppressed_frames,1,frames", "suppressed_message_bytes,6,bytes" }) assertTrue(row, csv.contains(row));
        assertTrue(csv.contains("audio_tx_audio_bytes,45,bytes")); assertTrue(csv.contains("idle_tx_control_bytes,100,bytes"));
        assertEquals(131, VoiceMetrics.framedBytes(125, true)); assertEquals(134, VoiceMetrics.framedBytes(126, true));
        assertEquals(130, VoiceMetrics.framedBytes(126, false));
        m.profile(true); assertTrue(m.csv().contains("tx_audio_bytes,0,bytes"));
        assertTrue(m.csv().contains("Baseline: no DTX"));
    }

    @Test public void recordingIsExplicitBoundedAndProducesValidPcmWav()
    {
        MicRecording r = new MicRecording(); short[] frame = { 1000, -1000, 32767, -32768 };
        r.append(frame); assertEquals(0, r.samples());
        r.start(); r.append(frame); r.start(); assertEquals(4, r.samples()); assertThrows(IllegalStateException.class, r::wav);
        r.stop(); r.append(frame); assertEquals(4, r.samples());
        byte[] wav = r.wav(); ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(52, wav.length); assertEquals(0x46464952, b.getInt(0)); assertEquals(48000, b.getInt(24));
        assertEquals(8, b.getInt(40)); for (int i = 0; i < frame.length; i++) assertEquals(frame[i], b.getShort(44 + i * 2));
        assertThrows(IllegalStateException.class, r::start);
        r.discard(); r.start();
        for (int i = 0; i < 3010; i++) r.append(new short[960]);
        assertFalse(r.active()); assertEquals(MicRecording.MAX_SAMPLES, r.samples());
        assertEquals(44 + MicRecording.MAX_SAMPLES * 2, r.wav().length);
        r.discard(); assertEquals(0, r.samples()); assertThrows(IllegalStateException.class, r::wav);
    }
}
