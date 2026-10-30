/* Copyright (c) 2006-2011 Skype Limited. All Rights Reserved
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

class EncodeAPI {

    /// <summary>
    /// Init or Reset encoder
    /// </summary>
    static void silk_InitEncoder(SilkEncoder encState) {
        SilkEncoder.silk_init_encoder(encState.state_Fxx[0]);
    }

    /// <summary>
    /// Encode one mono packet with Silk (no prefill, no FEC, variable bitrate)
    /// </summary>
    /// <param name="psEnc">I/O  State</param>
    /// <param name="encControl">I    Control status</param>
    /// <param name="samplesIn">I    Speech sample input vector</param>
    /// <param name="nSamplesIn">I    Number of samples in input vector</param>
    /// <param name="psRangeEnc">I/O  Compressor data structure</param>
    /// <param name="nBytesOut">I/O  Number of bytes in payload (input: Max bytes)</param>
    /// <returns>error code</returns>
    static int silk_Encode(
            SilkEncoder psEnc,
            EncControlState encControl,
            short[] samplesIn,
            int nSamplesIn,
            EntropyCoder psRangeEnc,
            BoxedValueInt nBytesOut) {
        int ret = SilkError.SILK_NO_ERROR;
        int nBits, flags, i;
        int nSamplesToBuffer, nSamplesToBufferMax, nBlocksOf10ms;
        int nSamplesFromInput, nSamplesFromInputMax;
        int TargetRate_bps;
        short[] buf;
        int curr_block, tot_blocks;
        SilkChannelEncoder state = psEnc.state_Fxx[0];
        nBytesOut.Val = 0;
        state.nFramesEncoded = 0;

        nBlocksOf10ms = Inlines.silk_DIV32(100 * nSamplesIn, encControl.API_sampleRate);
        tot_blocks = (nBlocksOf10ms > 1) ? nBlocksOf10ms >> 1 : 1;
        curr_block = 0;
        /* Only accept input lengths that are a multiple of 10 ms, and no more than one packet */
        if (nBlocksOf10ms * encControl.API_sampleRate != 100 * nSamplesIn || nSamplesIn < 0
                || 1000 * nSamplesIn > encControl.payloadSize_ms * encControl.API_sampleRate) {
            return SilkError.SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES;
        }

        TargetRate_bps = encControl.bitRate;
        ret += state.silk_control_encoder(encControl, TargetRate_bps);
        if (ret != SilkError.SILK_NO_ERROR) {
            return ret;
        }
        state.inDTX = state.useDTX;

        /* Input buffering/resampling and encoding */
        nSamplesToBufferMax = 10 * nBlocksOf10ms * state.fs_kHz;
        nSamplesFromInputMax = Inlines.silk_DIV32_16(nSamplesToBufferMax * state.API_fs_Hz, (short) (state.fs_kHz * 1000));

        buf = new short[nSamplesFromInputMax];

        int samplesIn_ptr = 0;
        while (true) {
            nSamplesToBuffer = state.frame_length - state.inputBufIx;
            nSamplesToBuffer = Inlines.silk_min(nSamplesToBuffer, nSamplesToBufferMax);
            nSamplesFromInput = Inlines.silk_DIV32_16(nSamplesToBuffer * state.API_fs_Hz, state.fs_kHz * 1000);

            /* Resample and write to buffer */
            System.arraycopy(samplesIn, samplesIn_ptr, buf, 0, nSamplesFromInput);
            ret += Resampler.silk_resampler(state.resampler_state, state.inputBuf, state.inputBufIx + 2, buf, 0, nSamplesFromInput);
            state.inputBufIx += nSamplesToBuffer;

            samplesIn_ptr += nSamplesFromInput;
            nSamplesIn -= nSamplesFromInput;

            /* Silk encoder */
            if (state.inputBufIx >= state.frame_length) {
                /* Enough data in input buffer, so encode */
                Inlines.OpusAssert(state.inputBufIx == state.frame_length);

                if (state.nFramesEncoded == 0) {
                    /* Create space at start of payload for VAD and FEC flags */
                    short[] iCDF = {0, 0};
                    iCDF[0] = (short) (256 - Inlines.silk_RSHIFT(256, state.nFramesPerPacket + 1));
                    psRangeEnc.enc_icdf(0, iCDF, 8);
                    /* No LBRR data: FEC is off */
                    state.LBRR_flag = 0;
                    psEnc.nBitsUsedLBRR = psRangeEnc.tell();
                }

                HPVariableCutoff.silk_HP_variable_cutoff(psEnc.state_Fxx);

                /* Total target bits for packet */
                nBits = Inlines.silk_DIV32_16(Inlines.silk_MUL(encControl.bitRate, encControl.payloadSize_ms), 1000);

                /* Subtract bits used for LBRR */
                nBits -= psEnc.nBitsUsedLBRR;

                /* Divide by number of uncoded frames left in packet */
                nBits = Inlines.silk_DIV32_16(nBits, state.nFramesPerPacket);

                /* Convert to bits/second */
                if (encControl.payloadSize_ms == 10) {
                    TargetRate_bps = Inlines.silk_SMULBB(nBits, 100);
                } else {
                    TargetRate_bps = Inlines.silk_SMULBB(nBits, 50);
                }

                /* Subtract fraction of bits in excess of target in previous frames and packets */
                TargetRate_bps -= Inlines.silk_DIV32_16(Inlines.silk_MUL(psEnc.nBitsExceeded, 1000), TuningParameters.BITRESERVOIR_DECAY_TIME_MS);

                if (state.nFramesEncoded > 0) {
                    /* Compare actual vs target bits so far in this packet */
                    int bitsBalance = psRangeEnc.tell() - psEnc.nBitsUsedLBRR - nBits * state.nFramesEncoded;
                    TargetRate_bps -= Inlines.silk_DIV32_16(Inlines.silk_MUL(bitsBalance, 1000), TuningParameters.BITRESERVOIR_DECAY_TIME_MS);
                }

                /* Never exceed input bitrate */
                TargetRate_bps = Inlines.silk_LIMIT(TargetRate_bps, encControl.bitRate, 5000);

                /* Buffering */
                System.arraycopy(psEnc.sMid, 0, state.inputBuf, 0, 2);
                System.arraycopy(state.inputBuf, state.frame_length, psEnc.sMid, 0, 2);

                state.silk_encode_do_VAD();

                /* Handling rate constraints */
                int maxBits = encControl.maxBits;
                if (tot_blocks == 2 && curr_block == 0) {
                    maxBits = maxBits * 3 / 5;
                } else if (tot_blocks == 3) {
                    if (curr_block == 0) {
                        maxBits = maxBits * 2 / 5;
                    } else if (curr_block == 1) {
                        maxBits = maxBits * 3 / 4;
                    }
                }

                if (TargetRate_bps > 0) {
                    state.silk_control_SNR(TargetRate_bps);
                    /* Use independent coding if no previous frame available */
                    int condCoding = state.nFramesEncoded <= 0 ? SilkConstants.CODE_INDEPENDENTLY : SilkConstants.CODE_CONDITIONALLY;
                    ret += state.silk_encode_frame(nBytesOut, psRangeEnc, condCoding, maxBits);
                    Inlines.OpusAssert(ret == SilkError.SILK_NO_ERROR);
                }

                state.controlled_since_last_payload = 0;
                state.inputBufIx = 0;
                state.nFramesEncoded++;

                /* Insert VAD and FEC flags at beginning of bitstream */
                if (nBytesOut.Val > 0 && state.nFramesEncoded == state.nFramesPerPacket) {
                    flags = 0;
                    for (i = 0; i < state.nFramesPerPacket; i++) {
                        flags = Inlines.silk_LSHIFT(flags, 1);
                        flags |= (int) state.VAD_flags[i];
                    }
                    flags = Inlines.silk_LSHIFT(flags, 1);
                    flags |= (int) state.LBRR_flag;
                    psRangeEnc.enc_patch_initial_bits(flags, state.nFramesPerPacket + 1);

                    /* Return zero bytes if DTXed */
                    if (state.inDTX != 0) {
                        nBytesOut.Val = 0;
                    }

                    psEnc.nBitsExceeded += nBytesOut.Val * 8;
                    psEnc.nBitsExceeded -= Inlines.silk_DIV32_16(Inlines.silk_MUL(encControl.bitRate, encControl.payloadSize_ms), 1000);
                    psEnc.nBitsExceeded = Inlines.silk_LIMIT(psEnc.nBitsExceeded, 0, 10000);
                }

                if (nSamplesIn == 0) {
                    break;
                }
            } else {
                break;
            }

            curr_block++;
        }

        return ret;
    }
}
