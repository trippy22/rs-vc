package com.rsvc.audio;

import com.rsvc.VoiceObserver;

import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

/** Mic activity stays local; only frames with an eligible audience reach the encoder. */
final class CaptureProcessor {
    private final SpeechActivity activity;
    private final boolean suppressSilence;
    private OpusEncoder encoder;

    CaptureProcessor(SpeechActivity activity, boolean suppressSilence) {
        this.activity = activity;
        this.suppressSilence = suppressSilence;
    }

    byte[] process(short[] pcm, boolean audience, long nowMillis, VoiceObserver observer)
            throws OpusException {
        activity.observe(pcm, nowMillis);
        if (!audience) {
            // No encoded history from private, solo speech survives into a later transmission.
            encoder = null;
            observer.idle();
            return null;
        }
        if (encoder == null) {
            encoder = OpusCodec.encoder(suppressSilence);
        }
        byte[] encoded = OpusCodec.encode(encoder, pcm);
        boolean suppressed = suppressSilence && OpusCodec.suppress(encoded);
        observer.encoded(encoded.length, suppressed);
        return suppressed ? null : encoded;
    }
}
