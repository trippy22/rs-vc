package com.rsvc;

import org.concentus.OpusEncoder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioProtocolTest
{
    private static VoicePacket packet(long speaker, long sequence, byte[] opus)
    {
        return VoicePacket.downstream(ByteBuffer.allocate(opus.length + 10).put((byte) 1).putInt((int) speaker)
            .putInt((int) sequence).put((byte) 255).put(opus).array());
    }

    @Test public void pureJavaOpusEncodesAndDecodesAudiblePcm() throws Exception
    {
        OpusEncoder encoder = OpusCodec.encoder();
        org.concentus.OpusDecoder decoder = OpusCodec.decoder();
        long energy = 0;
        for (int frame = 0; frame < 20; frame++)
        {
            short[] pcm = new short[960];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (10000 * Math.sin(2 * Math.PI * 440 * (i + frame * 960) / 48000));
            byte[] encoded = OpusCodec.encode(encoder, pcm);
            assertTrue(encoded.length > 0 && encoded.length <= VoicePacket.MAX_OPUS);
            short[] output = new short[960]; assertEquals(960, decoder.decode(encoded, 0, encoded.length, output, 0, 960, false));
            for (short value : output) energy += (long) value * value;
        }
        assertTrue("Decoded speech must not be silence", energy > 1_000_000_000L);
    }

    @Test public void networkByteOrderAndUnsignedIdentifiersAgree()
    {
        VoicePacket packet = packet(0xffffffffL, 0xfffffffeL, new byte[] { 1, 2, 3 });
        assertEquals(0xffffffffL, packet.speaker); assertEquals(0xfffffffeL, packet.sequence); assertEquals(1f, packet.gain, 0);
        assertArrayEquals(new byte[] { 1, -1, -1, -1, -2, 1, 2, 3 }, VoicePacket.upstream(packet.sequence, packet.opus));
    }

    @Test public void malformedAndOversizedAudioIsRejected()
    {
        for (byte[] bytes : new byte[][] { new byte[0], new byte[10], new byte[411], new byte[20] })
            assertThrows(IllegalArgumentException.class, () -> VoicePacket.downstream(bytes));
    }

    @Test public void jitterBufferBoundsStreamsDropsBacklogAndExpiresAudio() throws Exception
    {
        PlaybackBuffer buffer = new PlaybackBuffer(); byte[] encoded = OpusCodec.encode(OpusCodec.encoder(), new short[960]);
        for (int id = 1; id <= 20; id++) buffer.offer(packet(id, 0, encoded), 1000);
        assertEquals(8, buffer.speakerCount());
        for (int n = 1; n < 100; n++) buffer.offer(packet(1, n, encoded), 1000);
        buffer.mix(1040, 100); buffer.mix(1300, 100); assertEquals(0, buffer.speakerCount());
        assertTrue(Arrays.equals(new short[960], buffer.mix(1400, 100)));
    }

    @Test public void insecureRemoteEndpointsAndCredentialUrlsAreRejected()
    {
        for (String url : new String[] { "ws://example.com/v1/voice", "wss://a:b@example.com/v1/voice", "wss://example.com/v1/voice?key=x", "https://example.com/v1/voice", "wss://example.com/other" })
            assertThrows(IllegalArgumentException.class, () -> VoiceConnection.validateEndpoint(url));
        VoiceConnection.validateEndpoint("wss://example.com/v1/voice"); VoiceConnection.validateEndpoint("ws://127.0.0.1:8787/v1/voice");
    }

    @Test public void privateChannelsRequireSafeNamesAndNonemptyPasswords()
    {
        assertEquals("my_friends-1", VoiceConnection.validateChannel(" My_Friends-1 "));
        for (String channel : new String[] { "", "ab", "a/b", "a".repeat(33), "friends\r\nInjected: x" })
            assertThrows(IllegalArgumentException.class, () -> VoiceConnection.validateChannel(channel));
        for (String password : new String[] { "", "short", "contains spaces", "a".repeat(129), "password\r\nInjected: x" })
            assertThrows(IllegalArgumentException.class, () -> VoiceConnection.validatePassword(password));
        VoiceConnection.validatePassword("local-test-password");
        VoiceConnection.validatePassword("secret");
    }
}
