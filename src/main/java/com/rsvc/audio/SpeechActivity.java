package com.rsvc.audio;

/** Visual audio activity only. Never gates, changes, or uploads audio. */
public final class SpeechActivity {
    private static final long HOLD_MILLIS = 200;
    private static final long MIN_RMS_SQUARED = 140L * 140;
    private volatile long lastActive = Long.MIN_VALUE;

    public void observe(short[] pcm, long nowMillis) {
        if (pcm.length == 0) {
            return;
        }
        long energy = 0;
        for (short sample : pcm) {
            energy += (long) sample * sample;
        }
        if (energy >= MIN_RMS_SQUARED * pcm.length) {
            lastActive = nowMillis;
        }
    }

    /** Marks speech decided elsewhere, such as an open voice-activation gate. */
    public void mark(long nowMillis) {
        lastActive = nowMillis;
    }

    public boolean active(long nowMillis) {
        long last = lastActive;
        return last != Long.MIN_VALUE && nowMillis >= last && nowMillis - last < HOLD_MILLIS;
    }

    public void clear() {
        lastActive = Long.MIN_VALUE;
    }
}
