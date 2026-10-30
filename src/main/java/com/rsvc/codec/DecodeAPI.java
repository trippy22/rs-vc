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

class DecodeAPI {

    /* Decode one 20 ms mono frame, or conceal it when lostFlag is set */
    static int silk_Decode( /* O    Returns error code                              */
            SilkDecoder psDec, /* I/O  State                                           */
            DecControlState decControl, /* I/O  Control Structure                               */
            int lostFlag, /* I    0: no loss, 1 loss                              */
            int newPacketFlag, /* I    Indicates first decoder call for this packet    */
            EntropyCoder psRangeDec, /* I/O  Compressor data structure                       */
            short[] samplesOut, /* O    Decoded output speech vector                    */
            int samplesOut_ptr,
            BoxedValueInt nSamplesOut /* O    Number of samples decoded                       */
    ) {
        int i, ret = SilkError.SILK_NO_ERROR;
        BoxedValueInt nSamplesOutDec = new BoxedValueInt(0);
        SilkChannelDecoder channel_state = psDec.channel_state;
        nSamplesOut.Val = 0;

        /* Test if first frame in payload */
        if (newPacketFlag != 0) {
            channel_state.nFramesDecoded = 0;
        }

        if (channel_state.nFramesDecoded == 0) {
            if (decControl.payloadSize_ms == 20) {
                channel_state.nFramesPerPacket = 1;
            } else if (decControl.payloadSize_ms == 40) {
                channel_state.nFramesPerPacket = 2;
            } else {
                return SilkError.SILK_DEC_INVALID_FRAME_SIZE;
            }
            channel_state.nb_subfr = SilkConstants.MAX_NB_SUBFR;
            ret += channel_state.silk_decoder_set_fs(16, decControl.API_sampleRate);
        }

        if (lostFlag == 0 && channel_state.nFramesDecoded == 0) {
            /* First decoder call for this payload: decode VAD flags and LBRR flag */
            for (i = 0; i < channel_state.nFramesPerPacket; i++) {
                channel_state.VAD_flags[i] = psRangeDec.dec_bit_logp(1);
            }
            if (psRangeDec.dec_bit_logp(1) != 0) {
                /* In-band FEC data: never sent by this encoder */
                return SilkError.SILK_DEC_PAYLOAD_ERROR;
            }
        }

        /* The decoded frame goes into the output buffer first; it is upsampled from a copy */
        int frame_ptr = samplesOut_ptr;

        /* Use independent coding if no previous frame available */
        int condCoding = channel_state.nFramesDecoded <= 0 ? SilkConstants.CODE_INDEPENDENTLY : SilkConstants.CODE_CONDITIONALLY;
        ret += channel_state.silk_decode_frame(psRangeDec, samplesOut, frame_ptr + 2, nSamplesOutDec, lostFlag, condCoding);
        channel_state.nFramesDecoded++;

        /* Buffering */
        System.arraycopy(psDec.sMid, 0, samplesOut, frame_ptr, 2);
        System.arraycopy(samplesOut, frame_ptr + nSamplesOutDec.Val, psDec.sMid, 0, 2);

        /* Number of output samples */
        nSamplesOut.Val = Inlines.silk_DIV32(nSamplesOutDec.Val * decControl.API_sampleRate, Inlines.silk_SMULBB(channel_state.fs_kHz, 1000));

        short[] frame = new short[channel_state.frame_length + 2];
        System.arraycopy(samplesOut, frame_ptr, frame, 0, channel_state.frame_length + 2);

        /* Resample decoded signal to API_sampleRate */
        ret += Resampler.silk_resampler(channel_state.resampler_state, samplesOut, samplesOut_ptr, frame, 1, nSamplesOutDec.Val);

        if (lostFlag != 0) {
            /* On packet loss, remove the gain clamping to prevent having the energy "bounce back"
               if we lose packets when the energy is going down */
            channel_state.LastGainIndex = 10;
        }

        return ret;
    }
}
