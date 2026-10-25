package com.rsvc.audio;

import com.rsvc.network.VoicePacket;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Bounded jitter queues and per-speaker decoders; all work is off the game thread. */
public final class PlaybackBuffer {
    private static final int MAX_SPEAKERS = 8;
    private final Map<Long, Stream> streams = new HashMap<>();
    private Map<Long, Integer> volumes = java.util.Collections.emptyMap();

    public synchronized void volumes(Map<Long, Integer> values) {
        volumes = new HashMap<>(values);
    }

    private static final class Stream {
        OpusDecoder decoder = OpusCodec.decoder();
        final ArrayDeque<VoicePacket> queue = new ArrayDeque<>();
        long receivedAt;
        long sequence = -1;
        boolean primed;
        final SpeechActivity activity = new SpeechActivity();

        Stream() throws OpusException {}
    }

    public synchronized void offer(VoicePacket packet, long now) throws OpusException {
        Stream stream = streams.get(packet.speaker);
        if (stream == null) {
            if (streams.size() >= MAX_SPEAKERS) {
                return;
            }
            stream = new Stream();
            streams.put(packet.speaker, stream);
        }
        long delta = (packet.sequence - stream.sequence) & 0xffffffffL;
        if (stream.sequence != -1 && (delta == 0 || delta >= 0x80000000L)) {
            return;
        }
        if (stream.sequence != -1 && delta > 1) {
            // Suppressed frames advance the sender's sequence. Start fresh after a
            // gap; never synthesize/replay a long silent interval or queued old speech.
            stream.decoder = OpusCodec.decoder();
            stream.queue.clear();
            stream.primed = false;
        }
        stream.sequence = packet.sequence;
        stream.receivedAt = now;
        // Never play a growing backlog of old speech after TCP stalls.
        if (stream.queue.size() >= 5) {
            stream.queue.clear();
            stream.primed = false;
        }
        stream.queue.add(packet);
    }

    public synchronized short[] mix(long now, int volume) {
        int[] sum = new int[OpusCodec.SAMPLES];
        Iterator<Stream> iter = streams.values().iterator();
        while (iter.hasNext()) {
            Stream stream = iter.next();
            if (now - stream.receivedAt > 200) {
                iter.remove();
                continue;
            }
            if (!stream.primed && stream.queue.size() < 2 && now - stream.receivedAt < 40) {
                continue;
            }
            stream.primed = true;
            VoicePacket packet = stream.queue.poll();
            if (packet == null) {
                stream.primed = false;
                continue;
            }
            try {
                short[] pcm = new short[OpusCodec.SAMPLES];
                int size =
                        stream.decoder.decode(
                                packet.opus, 0, packet.opus.length, pcm, 0, pcm.length, false);
                // Non-20ms packets are discarded even if a hostile relay supplies valid Opus.
                if (size != pcm.length) {
                    continue;
                }
                float gain =
                        packet.gain
                                * Math.max(0, Math.min(100, volume))
                                / 100f
                                * Math.max(
                                        0, Math.min(200, volumes.getOrDefault(packet.speaker, 100)))
                                / 100f;
                if (gain > 0) {
                    stream.activity.observe(pcm, now);
                }
                for (int i = 0; i < pcm.length; i++) {
                    sum[i] += (int) (pcm[i] * gain);
                }
            } catch (OpusException | RuntimeException ignored) {
                iter.remove();
            }
        }
        short[] result = new short[sum.length];
        for (int i = 0; i < sum.length; i++) {
            result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
        }
        return result;
    }

    public synchronized void clear() {
        streams.clear();
    }

    public synchronized void remove(long id) {
        streams.remove(id);
    }

    public synchronized java.util.Set<Long> speaking(long now) {
        java.util.Set<Long> result = new java.util.HashSet<>();
        streams.forEach(
                (id, stream) -> {
                    if (volumes.getOrDefault(id, 100) > 0 && stream.activity.active(now)) {
                        result.add(id);
                    }
                });
        return result;
    }

    synchronized int speakerCount() {
        return streams.size();
    }
}
