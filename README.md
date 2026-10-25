# OSRS Voice Chat

Talk to nearby players in RuneLite, or bring your friends into a private voice room.
Choose push-to-talk or voice activation, adjust each person's volume, and see who's
speaking without leaving the game.

![Voice chat sidebar with Connect, Disconnect, microphone controls, Room, Voices and Devices](plugin.png)

## Get talking

1. Enable **RS Voice Chat** in RuneLite's plugin list and log into Old School RuneScape.
   Voice connects automatically while the plugin is enabled.
2. Open the microphone tab in the sidebar. The **green dot** means you're connected;
   amber means connecting or retrying, and gray means disconnected.
3. Use **Devices** to choose your microphone and speakers. **Default** uses your
   system's default device. Headphones help prevent speaker audio feeding back into your mic.
4. Hold **Ctrl + V** to talk by default. Change the key or choose **Voice-activated**
   mode in the plugin settings.

**Connect** joins voice again after you disconnect; it is disabled while connecting
or connected. **Disconnect** stops your voice connection until you reconnect or
re-enable the plugin.

## Nearby voice

In **Nearby · 20 tiles**, you can talk to other connected plugin users within
20 tiles on the **same world and floor**. Move into range to join the conversation.

The **Voices** list shows random aliases instead of character names and scrolls
when more people are nearby. Each person's volume slider and **Mute** button affect
only what you hear. **Clear mutes** restores muted voices.

A green bubble above a visible player shows when they're speaking. When you're
alone, your own indicator can still react to your mic, but no audio is uploaded.

## Private rooms

Private rooms replace nearby voice. Everyone in the room can talk regardless of
distance, world or floor, as long as they stay logged in and connected.

- **Create:** click **Room → Create**. Your invite code appears immediately;
  select and copy it to share with your friends.
- **Join:** click **Room → Join**, paste an invite code, and confirm.
- **Find your code again:** click **Room → Invite code**.
- **Return to nearby voice:** click **Room → Leave room**.

Keep invite codes private: anyone with a valid code can join. A room stays open
while someone remains connected and is deleted when the last person leaves.
A server restart also clears rooms, so you may need to create a new one.

## Stay in control

- **Mute mic** stops your microphone.
- **Deafen** stops both incoming voice and your microphone.
- **Voice volume** sets overall playback volume; individual sliders let you balance people.
- **Speaking indicators** toggles the overhead bubbles and local mic detection when alone.

Voice activation follows RuneLite's window, including its sidebar and dialogs.
Switching to another application pauses microphone capture. Push-to-talk requires
game focus. Logging out pauses voice until you log back in.

Silence suppression reduces traffic when you aren't speaking. The plugin has no
recording or audio-export controls. Voice travels through an external relay over
encrypted connections; it is **not end-to-end encrypted**.

## Connection help

For normal use, leave **Use local test relay** **off**. That option is only for
running a test server on your own computer. If you use the separate development
helper, disable it when connecting to the hosted service.

If no one appears, check that they also have the plugin enabled and are either
within nearby range on your world/floor or in the same private room. Check your
voice mode, microphone selection, mute and deafen controls if you cannot be heard.
Temporary network failures reconnect automatically.

## Installation warning

> This plugin submits your IP address, RSN, account hash, in-game location, and live microphone audio to a 3rd-party server not controlled or verified by Runelite developers.

## License

BSD-2-Clause. Audio uses Concentus; its license and notices are included in
[third_party/concentus](third_party/concentus).
