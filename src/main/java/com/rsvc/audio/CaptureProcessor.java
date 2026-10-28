package com.rsvc.audio;

import com.rsvc.VoiceObserver;

import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mic activity stays local; only frames with an eligible audience reach the encoder. */
final class CaptureProcessor {
    /** An encoded packet; framesBefore is how many 20 ms frames it precedes the captured one. */
    static final class Packet {
        final byte[] opus;
        final int framesBefore;

        Packet(byte[] opus, int framesBefore) {
            this.opus = opus;
            this.framesBefore = framesBefore;
        }
    }

    private final SpeechActivity activity;
    private final boolean suppressSilence;
    private final NoiseGate gate;
    private OpusEncoder encoder;
    // The packet captured just before the gate opened; sent first so word onsets survive.
    private short[] preRoll;

    CaptureProcessor(SpeechActivity activity, boolean suppressSilence) {
        this(activity, suppressSilence, new NoiseGate());
    }

    CaptureProcessor(SpeechActivity activity, boolean suppressSilence, NoiseGate gate) {
        this.activity = activity;
        this.suppressSilence = suppressSilence;
        this.gate = gate;
    }

    /** Packets to send for this captured audio, oldest first: none, one, or pre-roll and one. */
    List<Packet> process(
            short[] pcm,
            boolean audience,
            long nowMillis,
            VoiceObserver observer,
            NoiseGate.Settings settings)
            throws OpusException {
        boolean admitted = gate.admit(pcm, nowMillis, settings);
        if (settings.enabled()) {
            // The local indicator shows what voice activation would send.
            if (admitted) {
                activity.mark(nowMillis);
            }
        } else {
            activity.observe(pcm, nowMillis);
        }
        if (!audience) {
            // No encoded history from private, solo speech survives into a later transmission.
            encoder = null;
            preRoll = null;
            gate.close();
            observer.idle();
            return Collections.emptyList();
        }
        if (!admitted) {
            preRoll = pcm;
            observer.idle();
            return Collections.emptyList();
        }
        if (encoder == null) {
            encoder = OpusCodec.encoder(suppressSilence);
        }
        List<Packet> packets = new ArrayList<>(2);
        if (preRoll != null) {
            byte[] earlier = encode(preRoll, observer);
            if (earlier != null) {
                packets.add(new Packet(earlier, preRoll.length / OpusCodec.SAMPLES));
            }
            preRoll = null;
        }
        byte[] current = encode(pcm, observer);
        if (current != null) {
            packets.add(new Packet(current, 0));
        }
        return packets;
    }

    private byte[] encode(short[] pcm, VoiceObserver observer) throws OpusException {
        byte[] encoded = OpusCodec.encode(encoder, pcm);
        boolean suppressed = suppressSilence && OpusCodec.suppress(encoded);
        observer.encoded(encoded.length, suppressed);
        return suppressed ? null : encoded;
    }
}
