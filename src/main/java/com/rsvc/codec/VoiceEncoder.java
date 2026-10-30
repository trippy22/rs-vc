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
 * Opus encoder restricted to SILK-only wideband mono at 48 kHz, 20 or 40 ms per packet: the
 * path opus_encode_native takes in that mode (VoIP high-pass, all bits to SILK, ToC byte,
 * trailing-zero trim), so packets match libopus and stay decodable by any Opus decoder.
 */
public final class VoiceEncoder {
    public static final int RATE = 48000;
    private static final int MAX_PACKET = 1276;
    // ToC bytes for SILK-only wideband mono, one frame: configs 9 (20 ms) and 10 (40 ms).
    static final int TOC_20MS = 0x48;
    static final int TOC_40MS = 0x50;

    private final SilkEncoder silk = new SilkEncoder();
    private final EncControlState control = new EncControlState();
    private final int[] hpMem = new int[4];
    private final int bitrate;
    private int hpSmoothQ15;

    /** SILK complexity 4 (two delayed-decision states) is the only one this port keeps. */
    public VoiceEncoder(int bitrate, boolean dtx) {
        if (bitrate < 6000 || bitrate > 40000) {
            throw new IllegalArgumentException("Unsupported bitrate");
        }
        EncodeAPI.silk_InitEncoder(silk);
        control.API_sampleRate = RATE;
        control.useDTX = dtx ? 1 : 0;
        this.bitrate = bitrate;
        hpSmoothQ15 = Inlines.silk_LSHIFT(Inlines.silk_lin2log(TuningParameters.VARIABLE_HP_MIN_CUTOFF_HZ), 8);
    }

    /** Encodes one 20 or 40 ms frame into out and returns the packet length; 1 is a DTX frame. */
    public int encode(short[] pcm, byte[] out) {
        int frameSize = pcm.length;
        if (frameSize != RATE / 50 && frameSize != RATE / 25) {
            throw new IllegalArgumentException("Unsupported frame size");
        }
        int maxBytes = Math.min(MAX_PACKET, out.length);
        int frameRate = RATE / frameSize;
        // Below this libopus would lower the internal rate or emit PLC frames; neither is supported.
        if (maxBytes * frameRate * 8 < 13000 || bitrate * frameSize / (RATE * 8) > maxBytes) {
            throw new IllegalArgumentException("Output buffer too small");
        }
        int bytesTarget = bitrate * frameSize / (RATE * 8) - 1;
        EntropyCoder enc = new EntropyCoder();
        enc.enc_init(out, 1, maxBytes - 1);

        // Variable high-pass, following the pitch SILK tracked in the previous frame.
        hpSmoothQ15 = Inlines.silk_SMLAWB(hpSmoothQ15, silk.state_Fxx[0].variable_HP_smth1_Q15 - hpSmoothQ15,
                ((int) ((TuningParameters.VARIABLE_HP_SMTH_COEF2) * ((long) 1 << (16)) + 0.5)));
        int cutoffHz = Inlines.silk_log2lin(Inlines.silk_RSHIFT(hpSmoothQ15, 8));
        short[] input = new short[frameSize];
        CodecHelpers.hp_cutoff(pcm, 0, cutoffHz, input, 0, hpMem, frameSize, 1, RATE);

        control.bitRate = 8 * bytesTarget * frameRate;
        control.payloadSize_ms = 1000 * frameSize / RATE;
        int nBytes = Math.min(1275, maxBytes - 1);
        control.maxBits = nBytes * 8;
        BoxedValueInt silkBytes = new BoxedValueInt(nBytes);
        if (EncodeAPI.silk_Encode(silk, control, input, frameSize, enc, silkBytes) != 0) {
            throw new IllegalStateException("SILK encode failed");
        }
        out[0] = (byte) (frameSize == RATE / 50 ? TOC_20MS : TOC_40MS);
        if (silkBytes.Val == 0) {
            return 1;
        }
        int length = (enc.tell() + 7) >> 3;
        enc.enc_done();
        if (enc.tell() > (maxBytes - 1) * 8) {
            // SILK busted its budget: send a PLC packet.
            out[1] = 0;
            return 2;
        }
        // The range decoder fills in trailing zeros, so SILK-only packets may drop them.
        while (length > 2 && out[length] == 0) {
            length--;
        }
        return length + 1;
    }
}
