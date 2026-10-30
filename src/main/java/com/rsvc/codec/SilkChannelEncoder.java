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

/// <summary>
/// Encoder state
/// </summary>
class SilkChannelEncoder {
    /* High pass filter state                                           */
    int variable_HP_smth1_Q15 = 0;
    /* State of first smoother                                          */
    int variable_HP_smth2_Q15 = 0;
    /* Low pass filter state                                            */
    final SilkVADState sVAD = new SilkVADState();
    /* Voice activity detector state                                    */
    final SilkNSQState sNSQ = new SilkNSQState();
    /* Noise Shape Quantizer State                                      */
    final short[] prev_NLSFq_Q15 = new short[SilkConstants.MAX_LPC_ORDER];
    /* Previously quantized NLSF vector                                 */
    int speech_activity_Q8 = 0;
    byte prevSignalType = 0;
    int prevLag = 0;
    int pitch_LPC_win_length = 0;
    int max_pitch_lag = 0;
    /* Highest possible pitch lag (samples)                             */
    int API_fs_Hz = 0;
    /* Soft request for sampling frequency (Hz)                */
    int fs_kHz = 0;
    /* Internal sampling frequency (kHz)                                */
    int nb_subfr = 0;
    /* Number of 5 ms subframes in a frame                              */
    int frame_length = 0;
    /* Frame length (samples)                                           */
    int subfr_length = 0;
    /* Subframe length (samples)                                        */
    int ltp_mem_length = 0;
    /* Length of LTP memory                                             */
    int la_pitch = 0;
    /* Look-ahead for pitch analysis (samples)                          */
    int la_shape = 0;
    /* Look-ahead for noise shape analysis (samples)                    */
    int shapeWinLength = 0;
    /* Window length for noise shape analysis (samples)                 */
    int TargetRate_bps = 0;
    /* Target bitrate (bps)                                             */
    int PacketSize_ms = 0;
    /* Number of milliseconds to put in each packet                     */
    int PacketLoss_perc = 0;
    /* Packet loss rate measured by farend                              */
    int frameCounter = 0;
    int Complexity = 0;
    /* Complexity setting                                               */
    int nStatesDelayedDecision = 0;
    /* Number of states in delayed decision quantization                */
    int useInterpolatedNLSFs = 0;
    /* Flag for using NLSF interpolation                                */
    int shapingLPCOrder = 0;
    /* Filter order for noise shaping filters                           */
    int predictLPCOrder = 0;
    /* Filter order for prediction filters                              */
    int pitchEstimationComplexity = 0;
    /* Complexity level for pitch estimator                             */
    int pitchEstimationLPCOrder = 0;
    /* Whitening filter order for pitch estimator                       */
    int pitchEstimationThreshold_Q16 = 0;
    /* Threshold for pitch estimator                                    */
    int LTPQuantLowComplexity = 0;
    /* Flag for low complexity LTP quantization                         */
    int mu_LTP_Q9 = 0;
    /* Rate-distortion tradeoff in LTP quantization                     */
    int sum_log_gain_Q7 = 0;
    /* Cumulative max prediction gain                                   */
    int NLSF_MSVQ_Survivors = 0;
    /* Number of survivors in NLSF MSVQ                                 */
    int first_frame_after_reset = 0;
    /* Flag for deactivating NLSF interpolation, pitch prediction       */
    int controlled_since_last_payload = 0;
    /* Flag for ensuring codec_control only runs once per packet        */
    int warping_Q16 = 0;
    /* Warping parameter for warped noise shaping                       */
    int useCBR = 0;
    /* Flag to indicate that only buffers are prefilled, no coding      */
    short[] pitch_lag_low_bits_iCDF = null;
    /* Pointer to iCDF table for low bits of pitch lag index            */
    short[] pitch_contour_iCDF = null;
    /* Pointer to iCDF table for pitch contour index                    */
    NLSFCodebook psNLSF_CB = null;
    /* Pointer to NLSF codebook                                         */
    final int[] input_quality_bands_Q15 = new int[SilkConstants.VAD_N_BANDS];
    int input_tilt_Q15 = 0;
    int SNR_dB_Q7 = 0;
    /* Quality setting                                                  */

    final byte[] VAD_flags = new byte[SilkConstants.MAX_FRAMES_PER_PACKET];
    byte LBRR_flag = 0;
    final int[] LBRR_flags = new int[SilkConstants.MAX_FRAMES_PER_PACKET];

    final SideInfoIndices indices = new SideInfoIndices();
    final byte[] pulses = new byte[SilkConstants.MAX_FRAME_LENGTH];

    /* Input/output buffering */
    final short[] inputBuf = new short[SilkConstants.MAX_FRAME_LENGTH + 2];
    /* Buffer containing input signal                                   */
    int inputBufIx = 0;
    int nFramesPerPacket = 0;
    int nFramesEncoded = 0;

    /* Specifically for entropy coding */
    int ec_prevSignalType = 0;
    short ec_prevLagIndex = 0;

    final SilkResamplerState resampler_state = new SilkResamplerState();

    /* DTX */
    int useDTX = 0;
    /* Flag to enable DTX                                               */
    int inDTX = 0;
    /* Flag to signal DTX period                                        */
    int noSpeechCounter = 0;
    /* Gains increment for coding LBRR frames                           */
    final SideInfoIndices[] indices_LBRR = new SideInfoIndices[SilkConstants.MAX_FRAMES_PER_PACKET];

    /* Noise shaping state */
    final SilkShapeState sShape = new SilkShapeState();

    /* Prefilter State */
    final SilkPrefilterState sPrefilt = new SilkPrefilterState();

    /* Buffer for find pitch and noise shape analysis */
    final short[] x_buf = new short[2 * SilkConstants.MAX_FRAME_LENGTH + SilkConstants.LA_SHAPE_MAX];

    /* Normalized correlation from pitch lag estimator */
    int LTPCorr_Q15 = 0;

    SilkChannelEncoder() {
        for (int c = 0; c < SilkConstants.MAX_FRAMES_PER_PACKET; c++) {
            indices_LBRR[c] = new SideInfoIndices();
        }
    }


    /// <summary>
    /// Control encoder: fixed 16 kHz internal rate and complexity 4
    /// </summary>
    int silk_control_encoder(EncControlState encControl, int TargetRate_bps) {
        this.useDTX = encControl.useDTX;
        this.API_fs_Hz = encControl.API_sampleRate;
        if (this.controlled_since_last_payload != 0) {
            return SilkError.SILK_NO_ERROR;
        }
        int ret = silk_setup_resamplers(16);
        ret += silk_setup_fs(16, encControl.payloadSize_ms);
        silk_setup_complexity();
        this.controlled_since_last_payload = 1;
        return ret;
    }


    private int silk_setup_resamplers(int fs_kHz) {
        if (this.fs_kHz == 0) {
            /* Initialize the resampler for enc_API.c preparing resampling from API_fs_Hz to fs_kHz */
            return Resampler.silk_resampler_init(this.resampler_state, this.API_fs_Hz, fs_kHz * 1000, 1);
        }
        return 0;
    }


    private int silk_setup_fs(
            int fs_kHz,
            int PacketSize_ms) {
        int ret = SilkError.SILK_NO_ERROR;

        /* Set packet size: 20 or 40 ms of 20 ms frames */
        if (PacketSize_ms != this.PacketSize_ms) {
            if (PacketSize_ms != 20 && PacketSize_ms != 40) {
                return SilkError.SILK_ENC_PACKET_SIZE_NOT_SUPPORTED;
            }
            this.nFramesPerPacket = Inlines.silk_DIV32_16(PacketSize_ms, SilkConstants.MAX_FRAME_LENGTH_MS);
            this.nb_subfr = SilkConstants.MAX_NB_SUBFR;
            this.frame_length = Inlines.silk_SMULBB(20, fs_kHz);
            this.pitch_LPC_win_length = Inlines.silk_SMULBB(SilkConstants.FIND_PITCH_LPC_WIN_MS, fs_kHz);
            this.pitch_contour_iCDF = SilkTables.silk_pitch_contour_iCDF;
            this.PacketSize_ms = PacketSize_ms;
            this.TargetRate_bps = 0;
            /* trigger new SNR computation */
        }

        /* Set sampling frequency (once: the internal rate never changes) */
        if (this.fs_kHz != fs_kHz) {
            this.inputBufIx = 0;
            this.nFramesEncoded = 0;
            this.TargetRate_bps = 0;

            /* Initialize non-zero parameters */
            this.prevLag = 100;
            this.first_frame_after_reset = 1;
            this.sPrefilt.lagPrev = 100;
            this.sShape.LastGainIndex = 10;
            this.sNSQ.lagPrev = 100;
            this.sNSQ.prev_gain_Q16 = 65536;
            this.prevSignalType = SilkConstants.TYPE_NO_VOICE_ACTIVITY;

            this.fs_kHz = fs_kHz;
            this.predictLPCOrder = SilkConstants.MAX_LPC_ORDER;
            this.psNLSF_CB = SilkTables.silk_NLSF_CB_WB;

            this.subfr_length = SilkConstants.SUB_FRAME_LENGTH_MS * fs_kHz;
            this.frame_length = Inlines.silk_SMULBB(this.subfr_length, this.nb_subfr);
            this.ltp_mem_length = Inlines.silk_SMULBB(SilkConstants.LTP_MEM_LENGTH_MS, fs_kHz);
            this.la_pitch = Inlines.silk_SMULBB(SilkConstants.LA_PITCH_MS, fs_kHz);
            this.max_pitch_lag = Inlines.silk_SMULBB(18, fs_kHz);
            this.pitch_LPC_win_length = Inlines.silk_SMULBB(SilkConstants.FIND_PITCH_LPC_WIN_MS, fs_kHz);
            this.mu_LTP_Q9 = ((int) ((TuningParameters.MU_LTP_QUANT_WB) * ((long) 1 << (9)) + 0.5))/*Inlines.SILK_CONST(TuningParameters.MU_LTP_QUANT_WB, 9)*/;
            this.pitch_lag_low_bits_iCDF = SilkTables.silk_uniform8_iCDF;
        }

        /* Check that settings are valid */
        Inlines.OpusAssert((this.subfr_length * this.nb_subfr) == this.frame_length);

        return ret;
    }


    /// <summary>
    /// Encoding complexity 4
    /// </summary>
    private void silk_setup_complexity() {
        this.pitchEstimationComplexity = SilkConstants.SILK_PE_MID_COMPLEX;
        this.pitchEstimationThreshold_Q16 = ((int) ((0.74f) * ((long) 1 << (16)) + 0.5))/*Inlines.SILK_CONST(0.74f, 16)*/;
        this.pitchEstimationLPCOrder = 10;
        this.shapingLPCOrder = 12;
        this.la_shape = 5 * this.fs_kHz;
        this.nStatesDelayedDecision = 2;
        this.useInterpolatedNLSFs = 1;
        this.LTPQuantLowComplexity = 0;
        this.NLSF_MSVQ_Survivors = 8;
        this.warping_Q16 = this.fs_kHz * ((int) ((TuningParameters.WARPING_MULTIPLIER) * ((long) 1 << (16)) + 0.5))/*Inlines.SILK_CONST(TuningParameters.WARPING_MULTIPLIER, 16)*/;

        /* Do not allow higher pitch estimation LPC order than predict LPC order */
        this.pitchEstimationLPCOrder = Inlines.silk_min_int(this.pitchEstimationLPCOrder, this.predictLPCOrder);
        this.shapeWinLength = SilkConstants.SUB_FRAME_LENGTH_MS * this.fs_kHz + 2 * this.la_shape;
        this.Complexity = 4;
    }




    /* Control SNR of residual quantizer */
    int silk_control_SNR(
            int TargetRate_bps /* I    Target max bitrate (bps)                    */
    ) {
        int k, ret = SilkError.SILK_NO_ERROR;
        int frac_Q6;
        int[] rateTable;

        /* Set bitrate/coding quality */
        TargetRate_bps = Inlines.silk_LIMIT(TargetRate_bps, SilkConstants.MIN_TARGET_RATE_BPS, SilkConstants.MAX_TARGET_RATE_BPS);
        if (TargetRate_bps != this.TargetRate_bps) {
            this.TargetRate_bps = TargetRate_bps;

            /* If new TargetRate_bps, translate to SNR_dB value */
            rateTable = SilkTables.silk_TargetRate_table_WB;

            /* Find bitrate interval in table and interpolate */
            for (k = 1; k < SilkConstants.TARGET_RATE_TAB_SZ; k++) {
                if (TargetRate_bps <= rateTable[k]) {
                    frac_Q6 = Inlines.silk_DIV32(Inlines.silk_LSHIFT(TargetRate_bps - rateTable[k - 1], 6),
                            rateTable[k] - rateTable[k - 1]);
                    this.SNR_dB_Q7 = Inlines.silk_LSHIFT(SilkTables.silk_SNR_table_Q1[k - 1], 6) + Inlines.silk_MUL(frac_Q6, SilkTables.silk_SNR_table_Q1[k] - SilkTables.silk_SNR_table_Q1[k - 1]);
                    break;
                }
            }
        }

        return ret;
    }

    void silk_encode_do_VAD() {
        /**
         * *************************
         */
        /* Voice Activity Detection */
        /**
         * *************************
         */
        VoiceActivityDetection.silk_VAD_GetSA_Q8(this, this.inputBuf, 1);

        /**
         * ***********************************************
         */
        /* Convert speech activity into VAD and DTX flags */
        /**
         * ***********************************************
         */
        if (this.speech_activity_Q8 < ((int) ((TuningParameters.SPEECH_ACTIVITY_DTX_THRES) * ((long) 1 << (8)) + 0.5))/*Inlines.SILK_CONST(TuningParameters.SPEECH_ACTIVITY_DTX_THRES, 8)*/) {
            this.indices.signalType = SilkConstants.TYPE_NO_VOICE_ACTIVITY;
            this.noSpeechCounter++;
            if (this.noSpeechCounter < SilkConstants.NB_SPEECH_FRAMES_BEFORE_DTX) {
                this.inDTX = 0;
            } else if (this.noSpeechCounter > SilkConstants.MAX_CONSECUTIVE_DTX + SilkConstants.NB_SPEECH_FRAMES_BEFORE_DTX) {
                this.noSpeechCounter = SilkConstants.NB_SPEECH_FRAMES_BEFORE_DTX;
                this.inDTX = 0;
            }
            this.VAD_flags[this.nFramesEncoded] = 0;
        } else {
            this.noSpeechCounter = 0;
            this.inDTX = 0;
            this.indices.signalType = SilkConstants.TYPE_UNVOICED;
            this.VAD_flags[this.nFramesEncoded] = 1;
        }
    }

    /**
     * *************
     */
    /* Encode frame */
    /**
     * *************
     */
    int silk_encode_frame(
            BoxedValueInt pnBytesOut, /* O    Pointer to number of payload bytes;                                         */
            EntropyCoder psRangeEnc, /* I/O  compressor data structure                                                   */
            int condCoding, /* I    The type of conditional coding to use                                       */
            int maxBits /* I    If > 0: maximum number of output bits                                       */
    ) {
        SilkEncoderControl sEncCtrl = new SilkEncoderControl();
        int i, iter, maxIter, found_upper, found_lower, ret = 0;
        int x_frame;
        EntropyCoder sRangeEnc_copy = new EntropyCoder();
        EntropyCoder sRangeEnc_copy2 = new EntropyCoder();
        SilkNSQState sNSQ_copy = new SilkNSQState();
        SilkNSQState sNSQ_copy2 = new SilkNSQState();
        int nBits, nBits_lower, nBits_upper, gainMult_lower, gainMult_upper;
        int gainsID, gainsID_lower, gainsID_upper;
        short gainMult_Q8;
        short ec_prevLagIndex_copy;
        int ec_prevSignalType_copy;
        byte LastGainIndex_copy2;
        byte seed_copy;

        /* This is totally unnecessary but many compilers (including gcc) are too dumb to realise it */
        LastGainIndex_copy2 = 0;
        nBits_lower = nBits_upper = gainMult_lower = gainMult_upper = 0;

        this.indices.Seed = (byte) (this.frameCounter++ & 3);

        /**
         * ***********************************************************
         */
        /* Set up Input Pointers, and insert frame in input buffer   */
        /**
         * **********************************************************
         */
        /* start of frame to encode */
        x_frame = this.ltp_mem_length;


        /**
         * ****************************************
         */
        /* Copy new frame to front of input buffer */
        /**
         * ****************************************
         */
        System.arraycopy(this.inputBuf, 1, this.x_buf, x_frame + SilkConstants.LA_SHAPE_MS * this.fs_kHz, this.frame_length);

        {
            int[] xfw_Q3;
            short[] res_pitch;
            byte[] ec_buf_copy;
            int res_pitch_frame;

            res_pitch = new short[this.la_pitch + this.frame_length + this.ltp_mem_length];
            /* start of pitch LPC residual frame */
            res_pitch_frame = this.ltp_mem_length;

            /**
             * **************************************
             */
            /* Find pitch lags, initial LPC analysis */
            /**
             * **************************************
             */
            FindPitchLags.silk_find_pitch_lags(this, sEncCtrl, res_pitch, this.x_buf, x_frame);

            /**
             * *********************
             */
            /* Noise shape analysis */
            /**
             * *********************
             */
            NoiseShapeAnalysis.silk_noise_shape_analysis(this, sEncCtrl, res_pitch, res_pitch_frame, this.x_buf, x_frame);

            /**
             * ************************************************
             */
            /* Find linear prediction coefficients (LPC + LTP) */
            /**
             * ************************************************
             */
            FindPredCoefs.silk_find_pred_coefs(this, sEncCtrl, res_pitch, this.x_buf, x_frame, condCoding);

            /**
             * *************************************
             */
            /* Process gains                        */
            /**
             * *************************************
             */
            ProcessGains.silk_process_gains(this, sEncCtrl, condCoding);

            /**
             * **************************************
             */
            /* Prefiltering for noise shaper         */
            /**
             * **************************************
             */
            xfw_Q3 = new int[this.frame_length];
            Filters.silk_prefilter(this, sEncCtrl, xfw_Q3, this.x_buf, x_frame);


            /* Loop over quantizer and entropy coding to control bitrate */
            maxIter = 6;
            gainMult_Q8 = (short) (((int) ((1) * ((long) 1 << (8)) + 0.5))/*Inlines.SILK_CONST(1, 8)*/);
            found_lower = 0;
            found_upper = 0;
            gainsID = GainQuantization.silk_gains_ID(this.indices.GainsIndices, this.nb_subfr);
            gainsID_lower = -1;
            gainsID_upper = -1;
            /* Copy part of the input state */
            sRangeEnc_copy.Assign(psRangeEnc);
            sNSQ_copy.Assign(this.sNSQ);
            seed_copy = this.indices.Seed;
            ec_prevLagIndex_copy = this.ec_prevLagIndex;
            ec_prevSignalType_copy = this.ec_prevSignalType;
            ec_buf_copy = new byte[1275]; // fixme: this size might be optimized to the actual size
            for (iter = 0;; iter++) {
                if (gainsID == gainsID_lower) {
                    nBits = nBits_lower;
                } else if (gainsID == gainsID_upper) {
                    nBits = nBits_upper;
                } else {
                    /* Restore part of the input state */
                    if (iter > 0) {
                        psRangeEnc.Assign(sRangeEnc_copy);
                        this.sNSQ.Assign(sNSQ_copy);
                        this.indices.Seed = seed_copy;
                        this.ec_prevLagIndex = ec_prevLagIndex_copy;
                        this.ec_prevSignalType = ec_prevSignalType_copy;
                    }

                    /**
                     * **************************************
                     */
                    /* Noise shaping quantization            */
                    /**
                     * **************************************
                     */
                    sNSQ.silk_NSQ_del_dec(
                            this,
                            this.indices,
                            xfw_Q3,
                            pulses,
                            sEncCtrl.PredCoef_Q12,
                            sEncCtrl.LTPCoef_Q14,
                            sEncCtrl.AR2_Q13,
                            sEncCtrl.HarmShapeGain_Q14,
                            sEncCtrl.Tilt_Q14,
                            sEncCtrl.LF_shp_Q14,
                            sEncCtrl.Gains_Q16,
                            sEncCtrl.pitchL,
                            sEncCtrl.Lambda_Q10,
                            sEncCtrl.LTP_scale_Q14);

                    /**
                     * *************************************
                     */
                    /* Encode Parameters                    */
                    /**
                     * *************************************
                     */
                    EncodeIndices.silk_encode_indices(this, psRangeEnc, this.nFramesEncoded, 0, condCoding);

                    /**
                     * *************************************
                     */
                    /* Encode Excitation Signal             */
                    /**
                     * *************************************
                     */
                    EncodePulses.silk_encode_pulses(psRangeEnc, this.indices.signalType, this.indices.quantOffsetType,
                            this.pulses, this.frame_length);

                    nBits = psRangeEnc.tell();

                    if (iter == 0 && nBits <= maxBits) {
                        break;
                    }
                }

                if (iter == maxIter) {
                    if (found_lower != 0 && (gainsID == gainsID_lower || nBits > maxBits)) {
                        /* Restore output state from earlier iteration that did meet the bitrate budget */
                        psRangeEnc.Assign(sRangeEnc_copy2);
                        Inlines.OpusAssert(sRangeEnc_copy2.offs <= 1275);
                        psRangeEnc.write_buffer(ec_buf_copy, 0, 0, (int) sRangeEnc_copy2.offs);
                        this.sNSQ.Assign(sNSQ_copy2);
                        this.sShape.LastGainIndex = LastGainIndex_copy2;
                    }
                    break;
                }

                if (nBits > maxBits) {
                    if (found_lower == 0 && iter >= 2) {
                        /* Adjust the quantizer's rate/distortion tradeoff and discard previous "upper" results */
                        sEncCtrl.Lambda_Q10 = Inlines.silk_ADD_RSHIFT32(sEncCtrl.Lambda_Q10, sEncCtrl.Lambda_Q10, 1);
                        found_upper = 0;
                        gainsID_upper = -1;
                    } else {
                        found_upper = 1;
                        nBits_upper = nBits;
                        gainMult_upper = gainMult_Q8;
                        gainsID_upper = gainsID;
                    }
                } else if (nBits < maxBits - 5) {
                    found_lower = 1;
                    nBits_lower = nBits;
                    gainMult_lower = gainMult_Q8;
                    if (gainsID != gainsID_lower) {
                        gainsID_lower = gainsID;
                        /* Copy part of the output state */
                        sRangeEnc_copy2.Assign(psRangeEnc);
                        Inlines.OpusAssert(psRangeEnc.offs <= 1275);
                        System.arraycopy(psRangeEnc.get_buffer(), 0, ec_buf_copy, 0, (int) psRangeEnc.offs);
                        sNSQ_copy2.Assign(this.sNSQ);
                        LastGainIndex_copy2 = this.sShape.LastGainIndex;
                    }
                } else {
                    /* Within 5 bits of budget: close enough */
                    break;
                }

                if ((found_lower & found_upper) == 0) {
                    /* Adjust gain according to high-rate rate/distortion curve */
                    int gain_factor_Q16;
                    gain_factor_Q16 = Inlines.silk_log2lin(Inlines.silk_LSHIFT(nBits - maxBits, 7) / this.frame_length + ((int) ((16) * ((long) 1 << (7)) + 0.5))/*Inlines.SILK_CONST(16, 7)*/);
                    gain_factor_Q16 = Inlines.silk_min_32(gain_factor_Q16, ((int) ((2) * ((long) 1 << (16)) + 0.5))/*Inlines.SILK_CONST(2, 16)*/);
                    if (nBits > maxBits) {
                        gain_factor_Q16 = Inlines.silk_max_32(gain_factor_Q16, ((int) ((1.3f) * ((long) 1 << (16)) + 0.5))/*Inlines.SILK_CONST(1.3f, 16)*/);
                    }

                    gainMult_Q8 = (short) (Inlines.silk_SMULWB(gain_factor_Q16, (int) gainMult_Q8));
                } else {
                    /* Adjust gain by interpolating */
                    gainMult_Q8 = (short) (gainMult_lower + Inlines.silk_DIV32_16(Inlines.silk_MUL(gainMult_upper - gainMult_lower, maxBits - nBits_lower), nBits_upper - nBits_lower));
                    /* New gain multplier must be between 25% and 75% of old range (note that gainMult_upper < gainMult_lower) */
                    if (gainMult_Q8 > Inlines.silk_ADD_RSHIFT32(gainMult_lower, gainMult_upper - gainMult_lower, 2)) {
                        gainMult_Q8 = (short) (Inlines.silk_ADD_RSHIFT32(gainMult_lower, gainMult_upper - gainMult_lower, 2));
                    } else if (gainMult_Q8 < Inlines.silk_SUB_RSHIFT32(gainMult_upper, gainMult_upper - gainMult_lower, 2)) {
                        gainMult_Q8 = (short) (Inlines.silk_SUB_RSHIFT32(gainMult_upper, gainMult_upper - gainMult_lower, 2));
                    }
                }

                for (i = 0; i < this.nb_subfr; i++) {
                    sEncCtrl.Gains_Q16[i] = Inlines.silk_LSHIFT_SAT32(Inlines.silk_SMULWB(sEncCtrl.GainsUnq_Q16[i], gainMult_Q8), 8);
                }

                /* Quantize gains */
                this.sShape.LastGainIndex = sEncCtrl.lastGainIndexPrev;
                BoxedValueByte boxed_gainIndex = new BoxedValueByte(this.sShape.LastGainIndex);
                GainQuantization.silk_gains_quant(this.indices.GainsIndices, sEncCtrl.Gains_Q16,
                        boxed_gainIndex, condCoding == SilkConstants.CODE_CONDITIONALLY ? 1 : 0, this.nb_subfr);
                this.sShape.LastGainIndex = boxed_gainIndex.Val;

                /* Unique identifier of gains vector */
                gainsID = GainQuantization.silk_gains_ID(this.indices.GainsIndices, this.nb_subfr);
            }
        }

        /* Update input buffer */
        Arrays.MemMove(this.x_buf, this.frame_length, 0, this.ltp_mem_length + SilkConstants.LA_SHAPE_MS * this.fs_kHz);

        /* Parameters needed for next frame */
        this.prevLag = sEncCtrl.pitchL[this.nb_subfr - 1];
        this.prevSignalType = this.indices.signalType;

        /**
         * *************************************
         */
        /* Finalize payload                     */
        /**
         * *************************************
         */
        this.first_frame_after_reset = 0;
        /* Payload size */
        pnBytesOut.Val = Inlines.silk_RSHIFT(psRangeEnc.tell() + 7, 3);

        return ret;
    }
}
