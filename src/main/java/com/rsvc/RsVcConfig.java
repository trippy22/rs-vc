package com.rsvc;

import com.rsvc.audio.NoiseGate;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

import java.awt.event.KeyEvent;

@ConfigGroup(RsVcConfig.GROUP)
public interface RsVcConfig extends Config {
    String GROUP = "rsvc";
    // Owned relay; the dev helper can still explicitly select loopback.
    String HOSTED_RELAY = "wss://51.81.34.93/v1/voice";

    @ConfigItem(
            keyName = "speakingIndicators",
            name = "Speaking indicators",
            position = 9,
            description =
                    "Show a green bubble above players while their voice is active. When alone,"
                        + " reads mic levels locally under your voice-mode controls; sends no audio"
                        + " without a listener.")
    default boolean speakingIndicators() {
        return true;
    }

    @ConfigItem(
            keyName = "voiceMode",
            name = "Voice mode",
            position = 8,
            description =
                    "Push-to-talk requires the key. Voice-activated captures automatically while"
                        + " RuneLite is focused and a listener or local speaking indicator is"
                        + " available, and sends only sound above the voice activation level."
                        + " Mute stops capture. Changing mode keeps your connection and room.")
    default VoiceMode voiceMode() {
        return VoiceMode.PUSH_TO_TALK;
    }

    @ConfigItem(
            keyName = "pushToTalk",
            name = "Push-to-talk",
            position = 2,
            description =
                    "In push-to-talk mode, hold while RuneLite is focused to transmit to nearby"
                            + " listeners.")
    default Keybind pushToTalk() {
        return new Keybind(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK);
    }

    @ConfigItem(
            keyName = "muteHotkey",
            name = "Toggle mic mute",
            position = 10,
            description = "Toggle microphone mute while the game has focus. Works in either voice mode.")
    default Keybind muteHotkey() {
        return new Keybind(KeyEvent.VK_F8, 0);
    }

    @ConfigItem(
            keyName = "deafenHotkey",
            name = "Toggle deafen",
            position = 11,
            description = "Toggle incoming voice and microphone mute while the game has focus. Works in either voice mode.")
    default Keybind deafenHotkey() {
        return new Keybind(KeyEvent.VK_F9, 0);
    }

    @ConfigItem(
            keyName = "voiceActivation",
            name = "Voice activation level",
            position = 12,
            description =
                    "Voice-activated mode only. Auto learns your background noise and sends sound"
                        + " about 12 dB above it. Manual sends sound above the threshold below."
                        + " The sidebar meter shows your mic level and where it opens.")
    default VoiceActivation voiceActivation() {
        return VoiceActivation.AUTO;
    }

    @Range(min = NoiseGate.MIN_MANUAL_DB, max = NoiseGate.MAX_MANUAL_DB)
    @Units(" dB")
    @ConfigItem(
            keyName = "voiceActivationThreshold",
            name = "Manual threshold",
            position = 13,
            description =
                    "With Manual voice activation, sound quieter than this is not sent. Lower is"
                        + " more sensitive. Speech is usually -30 to -15 dB; hiss and fans -70 to"
                        + " -50 dB.")
    default int voiceActivationThreshold() {
        return NoiseGate.DEFAULT_MANUAL_DB;
    }

    @Range(min = 0, max = 100)
    @ConfigItem(
            keyName = "volume",
            name = "Voice volume",
            position = 3,
            description = "Playback volume, 0–100 percent")
    default int volume() {
        return 80;
    }

    @ConfigItem(
            keyName = "microphone",
            name = "Microphone",
            position = 4,
            hidden = true,
            description = "Selected in Devices. Blank means Default.")
    default String microphone() {
        return "";
    }

    @ConfigItem(
            keyName = "speakers",
            name = "Speakers",
            position = 5,
            hidden = true,
            description = "Selected in Devices. Blank means Default.")
    default String speakers() {
        return "";
    }
}
