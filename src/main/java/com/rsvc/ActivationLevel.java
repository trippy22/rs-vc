package com.rsvc;

/** How the voice-activated gate picks its threshold; config key {@code voiceActivation}. */
public enum ActivationLevel {
    AUTO("Auto"),
    MANUAL("Manual");
    private final String label;

    ActivationLevel(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
