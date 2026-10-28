package com.rsvc;

public enum VoiceActivation {
    AUTO("Auto"),
    MANUAL("Manual");
    private final String label;

    VoiceActivation(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
