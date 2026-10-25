package com.rsvc;

public enum VoiceMode {
    PUSH_TO_TALK("Push-to-talk"),
    VOICE_ACTIVATED("Voice-activated");
    private final String label;

    VoiceMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
