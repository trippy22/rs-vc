package com.rsvc;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(RsVcConfig.GROUP)
public interface RsVcConfig extends Config
{
    String GROUP = "rsvc";

    @ConfigItem(keyName = "consent", name = "Allow voice connection", position = 0,
        description = "Sends your microphone audio while talking and RS name to your selected third-party relay while logged in. The relay sees your IP. Names/login status are self-reported; no world or location is sent. Local mic recording is off until started in Stats / test.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers")
    default boolean consent() { return false; }

    @ConfigItem(keyName = "endpoint", name = "Relay URL", position = 1, description = "Your deployed wss:// relay URL ending /v1/voice. Only loopback permits ws:// for testing. Changing this disconnects.")
    default String endpoint() { return ""; }

    @ConfigItem(keyName = "pushToTalk", name = "Push-to-talk", position = 2, description = "Hold while RuneLite is focused to transmit. Microphone is opened only while held.")
    default Keybind pushToTalk() { return new Keybind(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK); }

    @Range(min = 0, max = 100)
    @ConfigItem(keyName = "volume", name = "Voice volume", position = 3, description = "Playback volume, 0–100 percent")
    default int volume() { return 80; }

    @ConfigItem(keyName = "microphone", name = "Microphone", position = 4, description = "Exact Java Sound device name. Blank uses the system default. See the voice panel for available devices.")
    default String microphone() { return ""; }

    @ConfigItem(keyName = "speakers", name = "Speakers", position = 5, description = "Exact Java Sound device name. Blank uses the system default. Headphones recommended.")
    default String speakers() { return ""; }

    @ConfigItem(keyName = "baselineAudio", name = "Test baseline: no DTX", position = 7,
        description = "For bandwidth A/B tests only. Sends every 20ms frame while push-to-talk is held, including silence. Rejoin after changing; statistics reset when the audio profile changes.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers")
    default boolean baselineAudio() { return false; }
}
