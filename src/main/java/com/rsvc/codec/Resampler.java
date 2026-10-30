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

/*
 * Matrix of resampling methods used:
 *                                 Fs_out (kHz)
 *                        8      12     16     24     48
 *
 *               8        C      UF     U      UF     UF
 *              12        AF     C      UF     U      UF
 * Fs_in (kHz)  16        D      AF     C      UF     UF
 *              24        AF     D      AF     C      U
 *              48        AF     AF     AF     D      C
 *
 * C   . Copy (no resampling)
 * D   . Allpass-based 2x downsampling
 * U   . Allpass-based 2x upsampling
 * UF  . Allpass-based 2x upsampling followed by FIR interpolation
 * AF  . AR2 filter followed by FIR interpolation
 */
class Resampler {

    private static final int USE_silk_resampler_private_IIR_FIR = 2;
    private static final int USE_silk_resampler_private_down_FIR = 3;


    static int silk_resampler_init(
            SilkResamplerState S,
            int Fs_Hz_in,
            int Fs_Hz_out,
            int forEnc) {
        int up2x;

        if (forEnc != 0) {
            /* Encoder input: 48 kHz to 16 kHz, Fs_out : Fs_in = 1 : 3 */
            if (Fs_Hz_in != 48000 || Fs_Hz_out != 16000) {
                return -1;
            }
            S.inputDelay = 12;
            S.resampler_function = USE_silk_resampler_private_down_FIR;
            S.FIR_Fracs = 1;
            S.FIR_Order = SilkConstants.RESAMPLER_DOWN_ORDER_FIR2;
            S.Coefs = SilkTables.silk_Resampler_1_3_COEFS;
            up2x = 0;
        } else {
            /* Decoder output: 16 kHz to 48 kHz with the default resampler */
            if (Fs_Hz_in != 16000 || Fs_Hz_out != 48000) {
                return -1;
            }
            S.inputDelay = 7;
            S.resampler_function = USE_silk_resampler_private_IIR_FIR;
            up2x = 1;
        }

        S.Fs_in_kHz = Inlines.silk_DIV32_16(Fs_Hz_in, 1000);
        S.Fs_out_kHz = Inlines.silk_DIV32_16(Fs_Hz_out, 1000);

        /* Number of samples processed per batch */
        S.batchSize = S.Fs_in_kHz * SilkConstants.RESAMPLER_MAX_BATCH_SIZE_MS;

        /* Ratio of input/output samples */
        S.invRatio_Q16 = Inlines.silk_LSHIFT32(Inlines.silk_DIV32(Inlines.silk_LSHIFT32(Fs_Hz_in, 14 + up2x), Fs_Hz_out), 2);

        /* Make sure the ratio is rounded up */
        while (Inlines.silk_SMULWW(S.invRatio_Q16, Fs_Hz_out) < Inlines.silk_LSHIFT32(Fs_Hz_in, up2x)) {
            S.invRatio_Q16++;
        }

        return 0;
    }

    /// <summary>
    /// Resampler: convert from one sampling rate to another
    /// Input and output sampling rate are at most 48000 Hz
    /// </summary>
    /// <param name="S">I/O  Resampler state</param>
    /// <param name="output">O    Output signal</param>
    /// <param name="input">I    Input signal</param>
    /// <param name="inLen">I    Number of input samples</param>
    /// <returns></returns>
    static int silk_resampler(
            SilkResamplerState S,
            short[] output,
            int output_ptr,
            short[] input,
            int input_ptr,
            int inLen) {
        int nSamples;

        /* Need at least 1 ms of input data */
        Inlines.OpusAssert(inLen >= S.Fs_in_kHz);
        /* Delay can't exceed the 1 ms of buffering */
        Inlines.OpusAssert(S.inputDelay <= S.Fs_in_kHz);

        nSamples = S.Fs_in_kHz - S.inputDelay;

        short[] delayBufPtr = S.delayBuf;

        /* Copy to delay buffer */
        System.arraycopy(input, input_ptr, delayBufPtr, S.inputDelay, nSamples);

        if (S.resampler_function == USE_silk_resampler_private_IIR_FIR) {
            silk_resampler_private_IIR_FIR(S, output, output_ptr, delayBufPtr, 0, S.Fs_in_kHz);
            silk_resampler_private_IIR_FIR(S, output, output_ptr + S.Fs_out_kHz, input, input_ptr + nSamples, inLen - S.Fs_in_kHz);
        } else {
            silk_resampler_private_down_FIR(S, output, output_ptr, delayBufPtr, 0, S.Fs_in_kHz);
            silk_resampler_private_down_FIR(S, output, output_ptr + S.Fs_out_kHz, input, input_ptr + nSamples, inLen - S.Fs_in_kHz);
        }

        /* Copy to delay buffer */
        System.arraycopy(input, input_ptr + inLen - S.inputDelay, delayBufPtr, 0, S.inputDelay);

        return SilkError.SILK_NO_ERROR;
    }

    /// <summary>
    /// Downsample by a factor 2
    /// </summary>
    /// <param name="S">I/O  State vector [ 2 ]</param>
    /// <param name="output">O    Output signal [ floor(len/2) ]</param>
    /// <param name="input">I    Input signal [ len ]</param>
    /// <param name="inLen">I    Number of input samples</param>
    static void silk_resampler_down2(
            int[] S,
            short[] output,
            short[] input,
            int inLen) {
        int k, len2 = Inlines.silk_RSHIFT32(inLen, 1);
        int in32, out32, Y, X;

        Inlines.OpusAssert(SilkTables.silk_resampler_down2_0 > 0);
        Inlines.OpusAssert(SilkTables.silk_resampler_down2_1 < 0);

        /* Internal variables and state are in Q10 format */
        for (k = 0; k < len2; k++) {
            /* Convert to Q10 */
            in32 = Inlines.silk_LSHIFT((int) input[2 * k], 10);

            /* All-pass section for even input sample */
            Y = Inlines.silk_SUB32(in32, S[0]);
            X = Inlines.silk_SMLAWB(Y, Y, SilkTables.silk_resampler_down2_1);
            out32 = Inlines.silk_ADD32(S[0], X);
            S[0] = Inlines.silk_ADD32(in32, X);

            /* Convert to Q10 */
            in32 = Inlines.silk_LSHIFT((int) input[2 * k + 1], 10);

            /* All-pass section for odd input sample, and add to output of previous section */
            Y = Inlines.silk_SUB32(in32, S[1]);
            X = Inlines.silk_SMULWB(Y, SilkTables.silk_resampler_down2_0);
            out32 = Inlines.silk_ADD32(out32, S[1]);
            out32 = Inlines.silk_ADD32(out32, X);
            S[1] = Inlines.silk_ADD32(in32, X);

            /* Add, convert back to int16 and store to output */
            output[k] = (short) Inlines.silk_SAT16(Inlines.silk_RSHIFT_ROUND(out32, 11));
        }
    }

    /// <summary>
    /// Second order AR filter with single delay elements
    /// </summary>
    /// <param name="S">I/O  State vector [ 2 ]</param>
    /// <param name="out_Q8">O    Output signal</param>
    /// <param name="input">I    Input signal</param>
    /// <param name="A_Q14">I    AR coefficients, Q14</param>
    /// <param name="len">I    Signal length</param>
    static void silk_resampler_private_AR2(
            int[] S,
            int S_ptr,
            int[] out_Q8,
            int out_Q8_ptr,
            short[] input,
            int input_ptr,
            short[] A_Q14,
            int len) {
        int k, out32;

        for (k = 0; k < len; k++) {
            out32 = Inlines.silk_ADD_LSHIFT32(S[S_ptr], (int) input[input_ptr + k], 8);
            out_Q8[out_Q8_ptr + k] = out32;
            out32 = Inlines.silk_LSHIFT(out32, 2);
            S[S_ptr] = Inlines.silk_SMLAWB(S[S_ptr + 1], out32, A_Q14[0]);
            S[S_ptr + 1] = Inlines.silk_SMULWB(out32, A_Q14[1]);
        }
    }

    static int silk_resampler_private_down_FIR_INTERPOL(
            short[] output,
            int output_ptr,
            int[] buf,
            short[] FIR_Coefs,
            int FIR_Coefs_ptr,
            int max_index_Q16,
            int index_increment_Q16) {
        int index_Q16, res_Q6;
        int buf_ptr;

        for (index_Q16 = 0; index_Q16 < max_index_Q16; index_Q16 += index_increment_Q16) {
            /* Integer part gives pointer to buffered input */
            buf_ptr = Inlines.silk_RSHIFT(index_Q16, 16);

            /* Inner product */
            res_Q6 = Inlines.silk_SMULWB(Inlines.silk_ADD32(buf[buf_ptr + 0], buf[buf_ptr + 35]), FIR_Coefs[FIR_Coefs_ptr + 0]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 1], buf[buf_ptr + 34]), FIR_Coefs[FIR_Coefs_ptr + 1]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 2], buf[buf_ptr + 33]), FIR_Coefs[FIR_Coefs_ptr + 2]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 3], buf[buf_ptr + 32]), FIR_Coefs[FIR_Coefs_ptr + 3]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 4], buf[buf_ptr + 31]), FIR_Coefs[FIR_Coefs_ptr + 4]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 5], buf[buf_ptr + 30]), FIR_Coefs[FIR_Coefs_ptr + 5]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 6], buf[buf_ptr + 29]), FIR_Coefs[FIR_Coefs_ptr + 6]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 7], buf[buf_ptr + 28]), FIR_Coefs[FIR_Coefs_ptr + 7]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 8], buf[buf_ptr + 27]), FIR_Coefs[FIR_Coefs_ptr + 8]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 9], buf[buf_ptr + 26]), FIR_Coefs[FIR_Coefs_ptr + 9]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 10], buf[buf_ptr + 25]), FIR_Coefs[FIR_Coefs_ptr + 10]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 11], buf[buf_ptr + 24]), FIR_Coefs[FIR_Coefs_ptr + 11]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 12], buf[buf_ptr + 23]), FIR_Coefs[FIR_Coefs_ptr + 12]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 13], buf[buf_ptr + 22]), FIR_Coefs[FIR_Coefs_ptr + 13]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 14], buf[buf_ptr + 21]), FIR_Coefs[FIR_Coefs_ptr + 14]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 15], buf[buf_ptr + 20]), FIR_Coefs[FIR_Coefs_ptr + 15]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 16], buf[buf_ptr + 19]), FIR_Coefs[FIR_Coefs_ptr + 16]);
            res_Q6 = Inlines.silk_SMLAWB(res_Q6, Inlines.silk_ADD32(buf[buf_ptr + 17], buf[buf_ptr + 18]), FIR_Coefs[FIR_Coefs_ptr + 17]);

            /* Scale down, saturate and store in output array */
            output[output_ptr++] = (short) Inlines.silk_SAT16(Inlines.silk_RSHIFT_ROUND(res_Q6, 6));
        }

        return output_ptr;
    }

    /// <summary>
    /// Resample with a 2nd order AR filter followed by FIR interpolation
    /// </summary>
    /// <param name="S">I/O  Resampler state</param>
    /// <param name="output">O    Output signal</param>
    /// <param name="input">I    Input signal</param>
    /// <param name="inLen">I    Number of input samples</param>
    static void silk_resampler_private_down_FIR(
            SilkResamplerState S,
            short[] output,
            int output_ptr,
            short[] input,
            int input_ptr,
            int inLen) {
        int nSamplesIn;
        int max_index_Q16, index_increment_Q16;
        int[] buf = new int[S.batchSize + S.FIR_Order];

        /* Copy buffered samples to start of buffer */
        System.arraycopy(S.sFIR_i32, 0, buf, 0, S.FIR_Order);

        /* Iterate over blocks of frameSizeIn input samples */
        index_increment_Q16 = S.invRatio_Q16;
        while (true) {
            nSamplesIn = Inlines.silk_min(inLen, S.batchSize);

            /* Second-order AR filter (output in Q8) */
            silk_resampler_private_AR2(S.sIIR, 0, buf, S.FIR_Order, input, input_ptr, S.Coefs, nSamplesIn);

            max_index_Q16 = Inlines.silk_LSHIFT32(nSamplesIn, 16);

            /* Interpolate filtered signal */
            output_ptr = silk_resampler_private_down_FIR_INTERPOL(output, output_ptr, buf, S.Coefs, 2,
                    max_index_Q16, index_increment_Q16);

            input_ptr += nSamplesIn;
            inLen -= nSamplesIn;

            if (inLen > 1) {
                /* More iterations to do; copy last part of filtered signal to beginning of buffer */
                System.arraycopy(buf, nSamplesIn, buf, 0, S.FIR_Order);
            } else {
                break;
            }
        }

        /* Copy last part of filtered signal to the state for the next call */
        System.arraycopy(buf, nSamplesIn, S.sFIR_i32, 0, S.FIR_Order);
    }

    static int silk_resampler_private_IIR_FIR_INTERPOL(
            short[] output,
            int output_ptr,
            short[] buf,
            int max_index_Q16,
            int index_increment_Q16) {
        int index_Q16, res_Q15;
        int buf_ptr;
        int table_index;

        /* Interpolate upsampled signal and store in output array */
        for (index_Q16 = 0; index_Q16 < max_index_Q16; index_Q16 += index_increment_Q16) {
            table_index = Inlines.silk_SMULWB(index_Q16 & 0xFFFF, 12);
            buf_ptr = index_Q16 >> 16;

            res_Q15 = Inlines.silk_SMULBB(buf[buf_ptr], SilkTables.silk_resampler_frac_FIR_12[table_index][0]);
            res_Q15 = Inlines.silk_SMLABB(res_Q15, buf[buf_ptr + 1], SilkTables.silk_resampler_frac_FIR_12[table_index][1]);
            res_Q15 = Inlines.silk_SMLABB(res_Q15, buf[buf_ptr + 2], SilkTables.silk_resampler_frac_FIR_12[table_index][2]);
            res_Q15 = Inlines.silk_SMLABB(res_Q15, buf[buf_ptr + 3], SilkTables.silk_resampler_frac_FIR_12[table_index][3]);
            res_Q15 = Inlines.silk_SMLABB(res_Q15, buf[buf_ptr + 4], SilkTables.silk_resampler_frac_FIR_12[11 - table_index][3]);
            res_Q15 = Inlines.silk_SMLABB(res_Q15, buf[buf_ptr + 5], SilkTables.silk_resampler_frac_FIR_12[11 - table_index][2]);
            res_Q15 = Inlines.silk_SMLABB(res_Q15, buf[buf_ptr + 6], SilkTables.silk_resampler_frac_FIR_12[11 - table_index][1]);
            res_Q15 = Inlines.silk_SMLABB(res_Q15, buf[buf_ptr + 7], SilkTables.silk_resampler_frac_FIR_12[11 - table_index][0]);
            output[output_ptr++] = (short) Inlines.silk_SAT16(Inlines.silk_RSHIFT_ROUND(res_Q15, 15));
        }
        return output_ptr;
    }

    /// <summary>
    /// Upsample using a combination of allpass-based 2x upsampling and FIR interpolation
    /// </summary>
    /// <param name="S">I/O  Resampler state</param>
    /// <param name="output">O    Output signal</param>
    /// <param name="input">I    Input signal</param>
    /// <param name="inLen">I    Number of input samples</param>
    static void silk_resampler_private_IIR_FIR(
            SilkResamplerState S,
            short[] output,
            int output_ptr,
            short[] input,
            int input_ptr,
            int inLen) {
        int nSamplesIn;
        int max_index_Q16, index_increment_Q16;

        short[] buf = new short[2 * S.batchSize + SilkConstants.RESAMPLER_ORDER_FIR_12];

        /* Copy buffered samples to start of buffer */
        System.arraycopy(S.sFIR_i16, 0, buf, 0, SilkConstants.RESAMPLER_ORDER_FIR_12);

        /* Iterate over blocks of frameSizeIn input samples */
        index_increment_Q16 = S.invRatio_Q16;
        while (true) {
            nSamplesIn = Inlines.silk_min(inLen, S.batchSize);

            /* Upsample 2x */
            silk_resampler_private_up2_HQ(S.sIIR, buf, SilkConstants.RESAMPLER_ORDER_FIR_12, input, input_ptr, nSamplesIn);

            max_index_Q16 = Inlines.silk_LSHIFT32(nSamplesIn, 16 + 1);
            /* + 1 because 2x upsampling */
            output_ptr = silk_resampler_private_IIR_FIR_INTERPOL(output, output_ptr, buf, max_index_Q16, index_increment_Q16);
            input_ptr += nSamplesIn;
            inLen -= nSamplesIn;

            if (inLen > 0) {
                /* More iterations to do; copy last part of filtered signal to beginning of buffer */
                System.arraycopy(buf, nSamplesIn << 1, buf, 0, SilkConstants.RESAMPLER_ORDER_FIR_12);
            } else {
                break;
            }
        }

        /* Copy last part of filtered signal to the state for the next call */
        System.arraycopy(buf, nSamplesIn << 1, S.sFIR_i16, 0, SilkConstants.RESAMPLER_ORDER_FIR_12);
    }

    /// <summary>
    /// Upsample by a factor 2, high quality
    /// Uses 2nd order allpass filters for the 2x upsampling, followed by a
    /// notch filter just above Nyquist.
    /// </summary>
    /// <param name="S">I/O  Resampler state [ 6 ]</param>
    /// <param name="output">O    Output signal [ 2 * len ]</param>
    /// <param name="input">I    Input signal [ len ]</param>
    /// <param name="len">I    Number of input samples</param>
    static void silk_resampler_private_up2_HQ(
            int[] S,
            short[] output,
            int output_ptr,
            short[] input,
            int input_ptr,
            int len) {
        int k;
        int in32, out32_1, out32_2, Y, X;

        Inlines.OpusAssert(SilkTables.silk_resampler_up2_hq_0[0] > 0);
        Inlines.OpusAssert(SilkTables.silk_resampler_up2_hq_0[1] > 0);
        Inlines.OpusAssert(SilkTables.silk_resampler_up2_hq_0[2] < 0);
        Inlines.OpusAssert(SilkTables.silk_resampler_up2_hq_1[0] > 0);
        Inlines.OpusAssert(SilkTables.silk_resampler_up2_hq_1[1] > 0);
        Inlines.OpusAssert(SilkTables.silk_resampler_up2_hq_1[2] < 0);

        /* Internal variables and state are in Q10 format */
        for (k = 0; k < len; k++) {
            /* Convert to Q10 */
            in32 = Inlines.silk_LSHIFT((int) input[input_ptr + k], 10);

            /* First all-pass section for even output sample */
            Y = Inlines.silk_SUB32(in32, S[0]);
            X = Inlines.silk_SMULWB(Y, SilkTables.silk_resampler_up2_hq_0[0]);
            out32_1 = Inlines.silk_ADD32(S[0], X);
            S[0] = Inlines.silk_ADD32(in32, X);

            /* Second all-pass section for even output sample */
            Y = Inlines.silk_SUB32(out32_1, S[1]);
            X = Inlines.silk_SMULWB(Y, SilkTables.silk_resampler_up2_hq_0[1]);
            out32_2 = Inlines.silk_ADD32(S[1], X);
            S[1] = Inlines.silk_ADD32(out32_1, X);

            /* Third all-pass section for even output sample */
            Y = Inlines.silk_SUB32(out32_2, S[2]);
            X = Inlines.silk_SMLAWB(Y, Y, SilkTables.silk_resampler_up2_hq_0[2]);
            out32_1 = Inlines.silk_ADD32(S[2], X);
            S[2] = Inlines.silk_ADD32(out32_2, X);

            /* Apply gain in Q15, convert back to int16 and store to output */
            output[output_ptr + (2 * k)] = (short) Inlines.silk_SAT16(Inlines.silk_RSHIFT_ROUND(out32_1, 10));

            /* First all-pass section for odd output sample */
            Y = Inlines.silk_SUB32(in32, S[3]);
            X = Inlines.silk_SMULWB(Y, SilkTables.silk_resampler_up2_hq_1[0]);
            out32_1 = Inlines.silk_ADD32(S[3], X);
            S[3] = Inlines.silk_ADD32(in32, X);

            /* Second all-pass section for odd output sample */
            Y = Inlines.silk_SUB32(out32_1, S[4]);
            X = Inlines.silk_SMULWB(Y, SilkTables.silk_resampler_up2_hq_1[1]);
            out32_2 = Inlines.silk_ADD32(S[4], X);
            S[4] = Inlines.silk_ADD32(out32_1, X);

            /* Third all-pass section for odd output sample */
            Y = Inlines.silk_SUB32(out32_2, S[5]);
            X = Inlines.silk_SMLAWB(Y, Y, SilkTables.silk_resampler_up2_hq_1[2]);
            out32_1 = Inlines.silk_ADD32(S[5], X);
            S[5] = Inlines.silk_ADD32(out32_2, X);

            /* Apply gain in Q15, convert back to int16 and store to output */
            output[output_ptr + (2 * k) + 1] = (short) Inlines.silk_SAT16(Inlines.silk_RSHIFT_ROUND(out32_1, 10));
        }
    }
}
