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

class CodecHelpers {

    static void hp_cutoff(short[] input, int input_ptr, int cutoff_Hz, short[] output, int output_ptr, int[] hp_mem, int len, int channels, int Fs) {
        int[] B_Q28 = new int[3];
        int[] A_Q28 = new int[2];
        int Fc_Q19, r_Q28, r_Q22;

        Inlines.OpusAssert(cutoff_Hz <= Integer.MAX_VALUE / ((int) ((1.5f * 3.14159f / 1000) * ((long) 1 << (19)) + 0.5))/*Inlines.SILK_CONST(1.5f * 3.14159f / 1000, 19)*/);
        Fc_Q19 = Inlines.silk_DIV32_16(Inlines.silk_SMULBB(((int) ((1.5f * 3.14159f / 1000) * ((long) 1 << (19)) + 0.5))/*Inlines.SILK_CONST(1.5f * 3.14159f / 1000, 19)*/, cutoff_Hz), Fs / 1000);
        Inlines.OpusAssert(Fc_Q19 > 0 && Fc_Q19 < 32768);

        r_Q28 = ((int) ((1.0f) * ((long) 1 << (28)) + 0.5))/*Inlines.SILK_CONST(1.0f, 28)*/ - Inlines.silk_MUL(((int) ((0.92f) * ((long) 1 << (9)) + 0.5))/*Inlines.SILK_CONST(0.92f, 9)*/, Fc_Q19);

        /* b = r * [ 1; -2; 1 ]; */
 /* a = [ 1; -2 * r * ( 1 - 0.5 * Fc^2 ); r^2 ]; */
        B_Q28[0] = r_Q28;
        B_Q28[1] = Inlines.silk_LSHIFT(-r_Q28, 1);
        B_Q28[2] = r_Q28;

        /* -r * ( 2 - Fc * Fc ); */
        r_Q22 = Inlines.silk_RSHIFT(r_Q28, 6);
        A_Q28[0] = Inlines.silk_SMULWW(r_Q22, Inlines.silk_SMULWW(Fc_Q19, Fc_Q19) - ((int) ((2.0f) * ((long) 1 << (22)) + 0.5))/*Inlines.SILK_CONST(2.0f, 22)*/);
        A_Q28[1] = Inlines.silk_SMULWW(r_Q22, r_Q22);

        Filters.silk_biquad_alt(input, input_ptr, B_Q28, A_Q28, hp_mem, 0, output, output_ptr, len, channels);
        if (channels == 2) {
            Filters.silk_biquad_alt(input, input_ptr + 1, B_Q28, A_Q28, hp_mem, 2, output, output_ptr + 1, len, channels);
        }
    }
}
