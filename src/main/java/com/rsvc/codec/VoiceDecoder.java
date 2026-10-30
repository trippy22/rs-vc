/* Copyright (c) 2007-2008 CSIRO
   Copyright (c) 2007-2011 Xiph.Org Foundation
   Originally written by Jean-Marc Valin, Gregory Maxwell, Koen Vos,
   Timothy B. Terriberry, and the Opus open-source contributors
   Ported to Java by Logan Stromberg

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions
   are met:

   - Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

   - Neither the name of Internet Society, IETF or IETF Trust, nor the
   names of specific contributors, may be used to endorse or promote
   products derived from this software without specific prior written
   permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
   OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.rsvc.codec;

/**
 * Opus decoder for the packets VoiceEncoder makes: SILK-only wideband mono, one 20 or 40 ms
 * frame, played at 48 kHz. Follows opus_decode_frame for that mode, including SILK concealment.
 */
public final class VoiceDecoder {
    public static final int RATE = VoiceEncoder.RATE;
    private static final int F20 = RATE / 50;

    private final SilkDecoder silk = new SilkDecoder();
    private final DecControlState control = new DecControlState();
    private boolean decoded;

    public VoiceDecoder() {
        control.API_sampleRate = RATE;
    }

    /** Samples per channel in a packet this decoder accepts, or 0 for any other packet. */
    public static int samples(byte[] packet) {
        if (packet == null || packet.length < 1 || packet.length > 1276) {
            return 0;
        }
        int toc = packet[0] & 0xff;
        return toc == VoiceEncoder.TOC_20MS ? F20 : toc == VoiceEncoder.TOC_40MS ? 2 * F20 : 0;
    }

    /**
     * Decodes packet into pcm and returns the samples written. A null packet conceals pcm.length
     * samples (a multiple of 20 ms) of lost audio.
     */
    public int decode(byte[] packet, short[] pcm) {
        if (packet == null) {
            return conceal(pcm, pcm.length);
        }
        int frameSize = samples(packet);
        if (frameSize == 0 || pcm.length < frameSize) {
            throw new IllegalArgumentException("Unsupported packet");
        }
        if (packet.length <= 2) {
            // An empty payload is DTX or a PLC packet.
            return conceal(pcm, frameSize);
        }
        EntropyCoder dec = new EntropyCoder();
        dec.dec_init(packet, 1, packet.length - 1);
        control.payloadSize_ms = 1000 * frameSize / RATE;
        int done = 0;
        try {
            do {
                BoxedValueInt got = new BoxedValueInt(0);
                if (DecodeAPI.silk_Decode(silk, control, 0, done == 0 ? 1 : 0, dec, pcm, done, got) != 0) {
                    throw new IllegalArgumentException("Corrupt packet");
                }
                done += got.Val;
            } while (done < frameSize);
        } catch (AssertionError e) {
            // SILK's internal checks reject some malformed payloads; report them like any bad packet.
            throw new IllegalArgumentException("Corrupt packet", e);
        }
        decoded = true;
        return frameSize;
    }

    private int conceal(short[] pcm, int samples) {
        if (samples <= 0 || samples % F20 != 0 || samples > pcm.length) {
            throw new IllegalArgumentException("Concealment must be a multiple of 20 ms");
        }
        if (!decoded) {
            // Nothing to extrapolate from yet.
            java.util.Arrays.fill(pcm, 0, samples, (short) 0);
            return samples;
        }
        control.payloadSize_ms = 20;
        for (int start = 0; start < samples; start += F20) {
            BoxedValueInt got = new BoxedValueInt(0);
            if (DecodeAPI.silk_Decode(silk, control, 1, 1, null, pcm, start, got) != 0 || got.Val != F20) {
                // Concealment failure is not fatal: play silence for this frame.
                java.util.Arrays.fill(pcm, start, start + F20, (short) 0);
            }
        }
        return samples;
    }
}
