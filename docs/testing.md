# Acceptance testing

Automated checks do not prove that the plugin works correctly with real game
state, operating-system audio devices, or two players. Do not automate game input.
Use two consenting testers with headphones and development clients.

## Automated checks

- `./gradlew test`: actual Opus encode/decode, packet format, malformed packet
  rejection, bounded jitter queues, URL restrictions and reconnect backoff; also
  verifies low-bitrate mono/20 ms output and SILK/hybrid/CELT decoding/transitions.
  Compilation first verifies vendored source and license hashes.
  Tests also cover silence suppression/resumption, sequence gaps, control-message
  deduplication, counter states/framing, and explicit bounded WAV capture.
- `cd relay && npm test`: channel-wide routing without locations, stale heartbeats,
  deafen/mute, rate and size limits, sequence wraparound/replay, connection limits,
  restored attachment state, required passwords, cross-channel audio/roster
  isolation, six-character password acceptance, fail-closed configuration, legacy
  protocol rejection, membership-only rosters and a real Node WebSocket exchange.
- `npm run check`: Cloudflare bundle/config validation, no deployment.
- `./gradlew wireTest` with `npm run dev`: the production Java transport and actual
  codec through Cloudflare's local Worker/Durable Object emulator.

## Manual two-player gate (not yet completed)

For a same-machine development test, copy `relay/.dev.vars.example` to
`relay/.dev.vars` if that file does not exist, start `npm run dev` in `relay/`, then run
`./gradlew run` from the project root with JDK 11. Set the relay URL to
`ws://127.0.0.1:8787/v1/voice`. Two testers on different computers need the same
deployed WSS endpoint; localhost on each computer is a different relay.
For login, follow RuneLite's
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
instructions for the development client.

1. Start with consent off: verify no voice network connection or microphone access.
2. Configure one WSS relay for both testers. Enable consent, enter the same channel
   and password, and join explicitly. Locally use `friends` / `local-test-password`.
   Blank, incorrect passwords and unknown channels must fail. Join `other` /
   `other-test-password` on the local fixture: users in `friends` must be
   absent from both audio and the participant list. Rejoin the same channel to talk.
   Check the compact Join/Leave and Mute/Deafen rows fit the sidebar, including
   long status messages; the participant list should scroll independently.
   Configure a local test channel with a six-character password: it must work;
   five-character passwords must be rejected by both client and relay configuration.
3. Verify mic/speaker selection, volume, intelligible speech, and comfortable latency.
   The selected profile is now 16 kbps mono, 20 ms. Try quiet speech, consonants,
   background noise and simultaneous speakers; listen for clipping or muffling.
4. Join the same channel from distant locations, different floors and different
   worlds: both hear each other at unchanged volume while logged in.
5. Test scene loading/teleports and world hops: audio pauses during loading, then
   listening resumes when logged in. Release/repress PTT to speak again; no old
   audio plays. Logout or game connection loss disconnects voice; the login screen
   must not allow joining or audio. After logging in, explicitly join again.
6. Enter separate instances without any code: both hear each other once logged in.
   No instance setting should appear. Check the status returns from paused to idle.
7. Release push-to-talk, lose focus, mute, deafen and disable the plugin while
   speaking: transmission stops and the operating system mic indicator clears.
8. Verify per-participant mute and RuneLite ignores. Clear session mutes and confirm
   speech returns. Reconnect and confirm session mutes still apply by name.
9. Restart/redeploy the relay mid-speech: playback clears, the mic closes, backoff
   occurs, and holding the old key does not restart transmission. Release/repress.
10. Stop the relay long enough to exhaust retries: recovery stops and the UI asks
    for an explicit join. Wrong channel/password must not cause endless retries.
    Successful automatic recovery must retain the original private channel.
11. Change devices or endpoint, repeatedly join/leave, then disable the plugin:
    no lingering mic, audible output, sockets or device-worker activity.
    Re-enable the plugin: mute/deafen checkboxes and actual behavior must agree.
12. Test realistic poor Wi-Fi/packet loss and two people speaking at once. TCP can
    add latency; evaluate whether it is acceptable for the intended use.

## Manual bandwidth / own-mic recording gate (not yet completed)

1. With consent enabled and joined, open **Stats / test**, reset counters, leave
   push-to-talk released for 30 seconds, and save `idle.csv`. No outgoing audio
   packets should be counted; only small heartbeats/replies recur about every five
   seconds while membership is stable. Incoming speech
   from another tester counts separately even while your microphone is idle.
2. Reset, hold push-to-talk and read a paragraph for 30 seconds; save `talk.csv`.
   Repeat while quiet with push-to-talk held; save `silent-ptt.csv`. DTX detection
   has a warm-up/hangover period; most sustained silence frames should be omitted.
3. Speak, pause for several seconds while holding PTT, then speak softly and loudly.
   Verify first words return without stale playback, missing phrases or clicks.
   Test background noise and whispered speech; DTX is content-dependent.
4. For a live A/B test enable **Test baseline: no DTX**, rejoin, reset and repeat.
   Save each CSV before switching profiles (joining a changed profile resets stats).
   Disable baseline and rejoin when finished. Compare identical durations and peers;
   membership changes, incoming speakers and connection failures change totals.
5. Click **Record my mic** explicitly. Confirm the dialog says RECORDING; capture
   speech and silence while holding PTT. Release PTT: no extra samples are recorded.
   Received voices are excluded; use headphones to avoid physical speaker pickup.
   **Stop recording**, then **Save mic WAV**. Canceling the chooser writes nothing;
   saving over an existing filename must fail instead of overwriting it.
6. Open the saved WAV locally and verify it contains only your raw mic test input.
   The WAV omits released-key gaps and is not an Opus/received-audio quality recording.
   Run `tools/bandwidth-audit.ps1 -InputAudio <wav>` to compare settings on identical input.
7. Verify the 60-second cap, stopping when the dialog closes or voice disconnects,
   and discarding unsaved audio on plugin disable. Re-enable: recording must be off.
   File chooser/export failures must leave voice usable, with no game-thread stalls.

## Cloudflare beta gate (not yet completed)

Deploy to a Workers Free account, run a multi-user session, and record request,
duration and CPU quota consumption. Confirm no paid service was enabled. Verify
actual remote TLS/WebSocket behavior and recovery on a deployment. Hibernation
state restoration has a unit test, but production runtime behavior and daily
quota exhaustion have not been verified by local tests.

## Local audit result — October 23, 2026

- Checked production sources against the supplied root `Agents.MD`.
- Matched the exact consent warning; updated the broader audio/name/login
  disclosure and opt-in defaults.
- Cancelled capture/playback executors on shutdown; device closes still run
  off-thread before their cleanup executor stops. A key press racing shutdown
  cannot submit work to a stopped capture executor without being handled.
- Reset mute/deafen state when enabling the plugin and ignored stale connection
  status callbacks. Handled an unavailable character name when joining.
- Integrated 117 original Concentus source files; removed the Maven codec dependency.
  Preserved copyright/license notices and recorded source hashes/local diagnostic removal.
- Selected 16 kbps mono, voice hint and 20 ms frames, with the complete decoder.
  DTX now omits tiny silent packets; gaps reset bounded playback without modifying
  the full decoder. Removed position messages; heartbeats run about every five
  seconds and roster updates occur on membership changes.
- JDK 11 build/JAR and all 14 Java tests passed, including codec mode transitions
  and channel/password validation.
- All 20 relay tests and Cloudflare deployment dry run passed.
- Production Java transport sent and decoded paced speech/silence/resumed speech
  through the local Cloudflare emulator using the vendored 16 kbps codec and
  production playback buffer; 277 of 300 silent frames were omitted in this run;
  password rejection, channel-wide routing without location data, private-channel
  isolation, inactive-login gating, deafen and mute isolation passed.
- An 11.01-second connected-idle sample sent zero audio messages and
  exchanged 68 outgoing / 40 incoming control-message bytes (two heartbeats/replies),
  or 80 / 44 bytes with estimated WebSocket framing, before TLS/TCP/IP overhead.
  This is a short local sample with two other channel members,
  not a guaranteed rate for real sessions or a provider billing measurement.
- Reference audio plus six seconds of silence: suppression omitted 278/300 silent
  frames. Silence audio upload including estimated WebSocket framing fell from
  9.643 kbps (no DTX) to 0.725 kbps (DTX + suppression), excluding room-control
  traffic and TLS/TCP/IP. The offline tool also read a synthetic WAV produced by
  the recording class successfully; no physical microphone was opened.
- Rendered the compact Swing sidebar offline at the normal 242-pixel wrapped
  width with both connected and long error status text and a shorter viewport,
  using dummy callbacks.
  `./gradlew panelPreview` writes `build/panel-preview.png`; no game input is used.
- The built JAR contains 130 codec classes and both notice resources, excludes
  omitted/reference/test classes, and uses Java 11 bytecode throughout. Gradle's
  production runtime dependency list is empty (RuneLite supplies compile-only APIs).
- All six offline vendored-codec packet/PCM digests match the original source
  experiment, and cross-decoding with the former Maven codec passed.

These checks do not exercise physical audio devices, plugin UI/game lifecycle,
or a deployed Cloudflare service. The manual gates above remain open.
