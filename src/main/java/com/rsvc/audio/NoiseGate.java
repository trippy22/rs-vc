package com.rsvc.audio;

/**
 * Voice-activation gate on raw microphone levels. Opus DTX only stops near-digital silence, so
 * steady hiss or fan noise would otherwise transmit. Auto learns the background level while the
 * gate is closed and opens a fixed margin above it; manual uses a fixed threshold. Single capture
 * thread; the meter reads the volatile snapshot fields.
 */
public final class NoiseGate {
    /** Live gate configuration, read before every captured packet. */
    public interface Settings {
        Settings OFF = new Settings() {};

        default boolean enabled() {
            return false;
        }

        default boolean auto() {
            return true;
        }

        default int manualDb() {
            return DEFAULT_MANUAL_DB;
        }
    }

    public static final int MIN_MANUAL_DB = -70;
    public static final int MAX_MANUAL_DB = -20;
    public static final int DEFAULT_MANUAL_DB = -45;
    static final double SILENCE_DB = -100;
    // Auto opens this far above the learned background, within these bounds.
    static final double AUTO_MARGIN_DB = 12;
    static final double AUTO_MIN_DB = -60;
    static final double AUTO_MAX_DB = -30;
    private static final double FLOOR_MIN_DB = -75;
    private static final double INITIAL_FLOOR_DB = -60;
    // Quiet rooms are learned quickly; a louder background is accepted slowly, and slower still
    // while the gate is open, so ongoing speech is not mistaken for noise.
    private static final double FALL_PER_SECOND = 20;
    private static final double RISE_CLOSED_PER_SECOND = 3;
    private static final double RISE_OPEN_PER_SECOND = 1;
    // Stays open between words and through word endings; small dips below the threshold
    // (hysteresis) also keep it open.
    static final long HOLD_MILLIS = 400;
    private static final double HYSTERESIS_DB = 3;

    private double floor = INITIAL_FLOOR_DB;
    private boolean open;
    private long lastLoud;
    private volatile double levelDb = SILENCE_DB;
    private volatile double thresholdDb = INITIAL_FLOOR_DB + AUTO_MARGIN_DB;
    private volatile boolean gating;
    private volatile boolean reportedOpen;
    private volatile long updatedAt = Long.MIN_VALUE;

    /** RMS level in dBFS: 0 is a full-scale square wave, digital silence is {@link #SILENCE_DB}. */
    public static double level(short[] pcm) {
        if (pcm.length == 0) {
            return SILENCE_DB;
        }
        double sum = 0;
        for (short sample : pcm) {
            sum += (double) sample * sample;
        }
        double rms = Math.sqrt(sum / pcm.length) / 32768;
        return rms <= 0 ? SILENCE_DB : Math.max(SILENCE_DB, 20 * Math.log10(rms));
    }

    /** Measures one packet and returns whether it may be sent. */
    boolean admit(short[] pcm, long nowMillis, Settings settings) {
        double level = level(pcm);
        double seconds = pcm.length / (double) OpusCodec.RATE;
        boolean enabled = settings.enabled();
        double threshold =
                settings.auto()
                        ? Math.max(AUTO_MIN_DB, Math.min(AUTO_MAX_DB, floor + AUTO_MARGIN_DB))
                        : Math.max(MIN_MANUAL_DB, Math.min(MAX_MANUAL_DB, settings.manualDb()));
        if (level >= threshold) {
            open = true;
            lastLoud = nowMillis;
        } else if (open && level >= threshold - HYSTERESIS_DB) {
            lastLoud = nowMillis;
        } else if (open && nowMillis - lastLoud > HOLD_MILLIS) {
            open = false;
        }
        double target = Math.max(FLOOR_MIN_DB, level);
        if (target < floor) {
            floor = Math.max(target, floor - FALL_PER_SECOND * seconds);
        } else {
            double rise = (open ? RISE_OPEN_PER_SECOND : RISE_CLOSED_PER_SECOND) * seconds;
            floor = Math.min(target, floor + rise);
        }
        levelDb = level;
        thresholdDb = threshold;
        gating = enabled;
        reportedOpen = !enabled || open;
        updatedAt = nowMillis;
        return !enabled || open;
    }

    /** A new talk spurt must cross the threshold again; the learned background is kept. */
    void close() {
        open = false;
    }

    /** Latest measurement for the level meter, or null when the microphone is not capturing. */
    public Level snapshot(long nowMillis) {
        long at = updatedAt;
        if (at == Long.MIN_VALUE || nowMillis - at > 250) {
            return null;
        }
        return new Level(levelDb, gating ? thresholdDb : Double.NaN, reportedOpen);
    }

    void stopped() {
        updatedAt = Long.MIN_VALUE;
        open = false;
    }

    /** A meter reading. Threshold is NaN when the gate is not in use (push-to-talk). */
    public static final class Level {
        public final double levelDb;
        public final double thresholdDb;
        public final boolean open;

        public Level(double levelDb, double thresholdDb, boolean open) {
            this.levelDb = levelDb;
            this.thresholdDb = thresholdDb;
            this.open = open;
        }
    }
}
