package com.rsvc;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

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
            keyName = "localTesting",
            name = "Use local test relay",
            position = 6,
            description =
                    "Use ws://127.0.0.1:8787/v1/voice instead of the hosted relay."
                            + " Reconnects when changed.")
    default boolean localTesting() {
        return false;
    }

    @ConfigItem(
            keyName = "voiceMode",
            name = "Voice mode",
            position = 8,
            description =
                    "Push-to-talk requires the key. Voice-activated captures automatically while"
                        + " RuneLite is focused and a listener or local speaking indicator is"
                        + " available; DTX suppresses silence but noise may transmit. Mute stops"
                        + " capture. Changing mode keeps your connection and room.")
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
