package com.rsvc.audio;

import com.rsvc.codec.VoiceDecoder;
import com.rsvc.network.VoicePacket;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Bounded jitter queues and per-speaker decoders; all work is off the game thread. */
public final class PlaybackBuffer {
    private static final int MAX_SPEAKERS = 8;
    // Queued audio is counted in 20 ms playback frames; a 40 ms packet counts twice.
    private static final int MAX_QUEUED_FRAMES = 5;
    private static final int PRIMED_FRAMES = 2;
    // Short sequence gaps are lost audio and are concealed. Longer gaps are omitted silence.
    private static final int MAX_CONCEALED_GAP = 3;
    // Concealment played when a primed stream runs dry, so speech fades instead of clicking.
    private static final int UNDERRUN_CONCEALMENT = 2;
    private final Map<Long, Stream> streams = new HashMap<>();
    private Map<Long, Integer> volumes = java.util.Collections.emptyMap();

    public synchronized void volumes(Map<Long, Integer> values) {
        volumes = new HashMap<>(values);
    }

    private static final class Frame {
        final byte[] opus; // Null for a lost frame the decoder must conceal.
        final int frames;

        Frame(byte[] opus, int frames) {
            this.opus = opus;
            this.frames = frames;
        }
    }

    private static final class Stream {
        VoiceDecoder decoder = OpusCodec.decoder();
        final ArrayDeque<Frame> queue = new ArrayDeque<>();
        int queuedFrames;
        short[] carry; // Second half of a decoded 40 ms packet; counted in queuedFrames.
        long receivedAt;
        long sequence = -1;
        int packetFrames = 1;
        int concealed;
        boolean primed;
        final SpeechActivity activity = new SpeechActivity();

        void reset() {
            decoder = OpusCodec.decoder();
            queue.clear();
            queuedFrames = 0;
            carry = null;
            concealed = 0;
            primed = false;
        }

        void dropOldest() {
            if (carry != null) {
                carry = null;
                queuedFrames--;
            } else {
                queuedFrames -= queue.poll().frames;
            }
        }
    }

    public synchronized void offer(VoicePacket packet, long now) {
        int frames = frames(packet.opus);
        if (frames == 0) {
            // Only 20 and 40 ms packets are played, even if a hostile relay supplies valid Opus.
            return;
        }
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
        if (stream.sequence != -1) {
            // Sequences count 20 ms frames, including suppressed ones, so a long gap is
            // silence. Start fresh then; never synthesize/replay a long silent interval.
            long missing = delta - stream.packetFrames;
            if (missing > MAX_CONCEALED_GAP) {
                stream.reset();
            } else {
                for (long n = 0; n < missing; n++) {
                    stream.queue.add(new Frame(null, 1));
                    stream.queuedFrames++;
                }
            }
        }
        stream.sequence = packet.sequence;
        stream.packetFrames = frames;
        stream.receivedAt = now;
        stream.queue.add(new Frame(packet.opus, frames));
        stream.queuedFrames += frames;
        // Never play a growing backlog of old speech after TCP stalls. Keep the newest
        // audio at the priming depth instead of discarding everything and re-priming.
        if (stream.queuedFrames > MAX_QUEUED_FRAMES) {
            while (stream.queuedFrames > PRIMED_FRAMES) {
                stream.dropOldest();
            }
        }
    }

    private static int frames(byte[] opus) {
        // Zero for anything but the codec's own SILK-only wideband mono packets.
        return VoiceDecoder.samples(opus) / OpusCodec.SAMPLES;
    }

    public synchronized short[] mix(long now, int volume) {
        int[] sum = new int[OpusCodec.SAMPLES];
        Iterator<Map.Entry<Long, Stream>> iter = streams.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, Stream> entry = iter.next();
            Stream stream = entry.getValue();
            if (now - stream.receivedAt > 200) {
                iter.remove();
                continue;
            }
            short[] pcm;
            boolean real;
            if (stream.carry != null) {
                pcm = stream.carry;
                real = true;
                stream.carry = null;
                stream.queuedFrames--;
            } else {
                if (!stream.primed
                        && stream.queuedFrames < PRIMED_FRAMES
                        && now - stream.receivedAt < 40) {
                    continue;
                }
                Frame frame = stream.queue.poll();
                if (frame == null) {
                    if (!stream.primed || stream.concealed >= UNDERRUN_CONCEALMENT) {
                        stream.primed = false;
                        continue;
                    }
                    stream.concealed++;
                    frame = new Frame(null, 1);
                } else {
                    stream.queuedFrames -= frame.frames;
                }
                stream.primed = true;
                real = frame.opus != null;
                if (real) {
                    stream.concealed = 0;
                }
                try {
                    short[] decoded = new short[frame.frames * OpusCodec.SAMPLES];
                    int size = stream.decoder.decode(frame.opus, decoded);
                    if (size != decoded.length) {
                        continue;
                    }
                    if (frame.frames == 2) {
                        pcm = java.util.Arrays.copyOf(decoded, OpusCodec.SAMPLES);
                        stream.carry =
                                java.util.Arrays.copyOfRange(
                                        decoded, OpusCodec.SAMPLES, decoded.length);
                        stream.queuedFrames++;
                    } else {
                        pcm = decoded;
                    }
                } catch (RuntimeException ignored) {
                    iter.remove();
                    continue;
                }
            }
            float gain =
                    Math.max(0, Math.min(100, volume))
                            / 100f
                            * Math.max(0, Math.min(200, volumes.getOrDefault(entry.getKey(), 100)))
                            / 100f;
            if (gain > 0 && real) {
                stream.activity.observe(pcm, now);
            }
            for (int i = 0; i < pcm.length; i++) {
                sum[i] += (int) (pcm[i] * gain);
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

    synchronized int queuedFrames(long speaker) {
        Stream stream = streams.get(speaker);
        return stream == null ? 0 : stream.queuedFrames;
    }
}
