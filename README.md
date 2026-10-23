# RS Voice Chat

Opt-in private channel voice entirely inside RuneLite: Java Sound → pure-Java
Concentus/Opus → encrypted WebSocket → channel participants. No browser, native
codec, companion process, or audio recording is required.

Voice is private-channel only: joining requires a channel name and its password.
The relay owner creates channels; the sidebar does not create public rooms.
Participants can talk across any distance, world, floor or instance while logged in.
Other channels' participants are hidden.

Voice uses 16 kbps variable-bitrate mono Opus with a voice signal hint, 48 kHz
capture/playback and 20 ms frames (960 samples). DTX is enabled: during push-to-talk,
Opus packets of at most two bytes are suppressed instead of sent. Other packets,
including periodic DTX updates, still pass through. Nominal Opus payload is 7.2 MB per speaking hour, before
protocol/network overhead and VBR variation. The complete single-stream decoder
is retained, including SILK, hybrid and CELT modes.

This is a development build, not an approved Plugin Hub release. Automated codec,
relay and synthetic end-to-end tests are included. Two-player in-game testing and
Plugin Hub review are still required before a public release.

## Run locally

This Git repository contains the plugin, tests, documentation and vendored codec.
The entire `relay/` directory is local-only and ignored by Git. Relay commands below
apply when that separately maintained directory is available; a fresh clone needs
an existing compatible relay endpoint or a separate copy of the relay. Building
and running Java unit tests does not require relay files. CI runs those Java checks;
`wireTest` additionally requires a running compatible relay with test channels.

Requirements: JDK 11 (set `JAVA_HOME`), Node.js 22 or newer, and RuneLite development
login access. On Windows use `gradlew.bat` and `npm.cmd` if PowerShell blocks npm.ps1.

Start the Cloudflare emulator in one terminal (loopback development only):

```sh
cd relay
npm ci
cp .dev.vars.example .dev.vars
npm run dev
```

On PowerShell use `Copy-Item .dev.vars.example .dev.vars` if the file does not
already exist. This example configures `friends` / `local-test-password` and
`other` / `other-test-password`. These public fixtures are only for local tests.
An unconfigured relay refuses all voice connections.

Start the plugin in another terminal, from this repository's root:

```sh
./gradlew run
```

In IntelliJ, select the shared **RS Voice Chat** run configuration (JDK 11), or
run the project's Gradle **run** task. Launching `RsVcPluginTest.main()` through
an automatically generated configuration requires VM option `-ea` and program
argument `--developer-mode`. RuneLite's builtin-plugin loader exits immediately
when assertions are disabled. A Gradle "non-zero exit value 1" footer is only a
summary; the application exception appears earlier in the run console.

For a Jagex Account, follow RuneLite's
[development login instructions](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).
The launcher is development-only; copying the plain build JAR into RuneLite does
not install a Plugin Hub plugin. The JAR includes the vendored codec but still
uses RuneLite's runtime and Plugin Hub packaging/review process.

1. Enable **RS Voice Chat** in the development client's plugin list.
2. Read and enable **Allow voice connection** in its settings.
3. Set **Relay URL** to `ws://127.0.0.1:8787/v1/voice` for a relay on the same machine.
4. Log into the game, open the microphone sidebar, enter channel `friends` and
   password `local-test-password`, and select **Join**.
5. Hold **Ctrl+V** to speak; configure another push-to-talk binding if preferred.
   The plugin does not consume or synthesize game key presses. Choose a binding
   that does not conflict with your game/chat controls.

The mic opens only during an eligible push-to-talk press. Release, focus loss,
mute/deafen, logout, loading transitions and disconnection stop transmission.
Remote endpoints require `wss://`; insecure `ws://` is permitted only on loopback.

## Cloudflare Free deployment

The Cloudflare adapter uses a Worker and **one SQLite-backed Durable Object** with
WebSocket hibernation. It does not use Realtime SFU, TURN, Containers, R2, a paid
database, or a custom domain. A `workers.dev` URL is sufficient.

```sh
cd relay
npm ci
npx wrangler login
npm run check
# Required: enter a JSON object mapping channel names to their passwords.
npx wrangler secret put CHANNELS_JSON
npm run deploy
```

Use the returned hostname in `wss://<worker>.<subdomain>.workers.dev/v1/voice`.
At the secret prompt, use the shape `{"friends":"<your-random-voice-password>"}`
with your own strong random password, never the local fixtures. Up to 32 channel
names are allowed: 3–32 lowercase letters, digits, `_` or `-`. Each password must
be 6–128 printable ASCII characters without spaces. Do not reuse a RuneScape or
other account password. Changing this secret updates the owner's channel list;
unconfigured, unknown or password-less channels cannot be joined.

Participants enter the channel and password in the sidebar. The password is kept
in memory for reconnection, cleared when leaving or recovery ends, and never
stored in RuneLite config or placed in the URL. The relay verifies it before
upgrading the WebSocket. The server binds that socket to the verified channel;
messages cannot switch channels. Passwords are not put in socket attachments.
Anyone possessing the channel password can join: this is not RS account authentication.

Keep the account on **Workers Free** if zero hosting charges are a hard requirement.
Free quotas are account-wide and can stop service until reset. The deployment
configuration alone does not force an already-paid account into the Free plan.
Do not enable paid upgrades expecting this code to enforce a monetary spending cap.
See [Cloudflare operations and limits](docs/cloudflare.md).

To use the portable Node adapter instead of the Cloudflare emulator, set
`CHANNELS_JSON` in the process environment. For loopback testing in PowerShell:

```powershell
cd relay
$env:CHANNELS_JSON='{"friends":"local-test-password","other":"other-test-password"}'
npm.cmd start
```

Both adapters default to port 8787; run only one at a time. The Node adapter reads
environment variables; only Wrangler reads `relay/.dev.vars`. Never commit that
file or real channel passwords. The former optional `ACCESS_TOKEN` is no longer
used; existing relays must configure `CHANNELS_JSON` before updated clients join.
Update the plugin and relay together: the channel-wide protocol uses version 2,
while the endpoint remains `/v1/voice`. Older location-based clients are rejected.

## Channel behavior and privacy

- Everyone in the same private channel can hear each other at full gain, regardless
  of distance, world, floor or instance. No instance code is needed.
- Maximum 64 connections per relay; at most four recent speakers forwarded per listener.
- Only the local player's own RS name, live audio and active-login flag are submitted.
  No coordinates, worlds, planes, instance identifiers, account hashes, account credentials,
  session tokens from RuneLite, friends lists or ignore lists are uploaded.
- The relay sends channel participants' names/ephemeral IDs when membership changes.
  Mute/ignore names remain local; blocked ephemeral voice IDs may be sent.
- The plugin permits audio only while logged in. Loading/world hops pause audio
  and release push-to-talk; once logged in again, release/repress to speak.
  Logout or a lost game connection disconnects voice and requires joining again.
  A stalled client stops local audio after 2.5 seconds; the relay stops routing
  audio after 15 seconds without a fresh heartbeat.
- The obsolete instance-code setting is removed; any old saved value is ignored.
  Existing supported config keys and the config group are unchanged.
- Names and login flags are self-reported and can be spoofed by modified clients.
  This is not a verified account-identity or anti-cheat service.
- Transport is TLS-encrypted in production, **not end-to-end encrypted**. The relay
  operator can access channel passwords, compressed voice and metadata. The provided relay does not
  write voice history to a database or log it. Cloudflare/host operators
  still see connection metadata. Socket attachments retain current state only.
- Recording is off by default. **Stats / test → Record my mic** explicitly arms
  a local raw-microphone test clip, only during eligible push-to-talk capture.
  It is limited to 60 seconds of captured audio in memory, never includes received
  streams, and writes a WAV only after **Stop recording → Save mic WAV** and a
  file selection. Released-key gaps are omitted. Closing the dialog, disconnecting
  or leaving stops recording; disabling the plugin discards the unsaved clip.
  Use headphones to avoid acoustic pickup of other participants through your mic.
- Mutes are scoped to the current join session and survive automatic reconnects
  by matching self-reported names. Leaving clears them. RuneLite ignores apply too.
- Use headphones: Java Sound + this Opus library does not provide acoustic echo cancellation.

## Verification

```sh
./gradlew test jar
cd relay
npm test
npm run check
```

With the local relay/emulator running, test the production Java transport with
synthetic audio (no microphone or game automation):

```sh
./gradlew wireTest
# Optional alternate local test endpoint:
./gradlew wireTest -PrelayUrl=ws://127.0.0.1:8788/v1/voice
```

The wire test sends paced speech/silence/resumed speech through real WebSockets,
decodes it through the production playback buffer, measures connected idle traffic,
and checks password rejection, channel isolation
(audio and roster), location-independent audio, inactive-login gating and deafen/mute isolation. It expects the two local
channels in `.dev.vars.example`; run against a loopback test relay. Optional
`RSVC_TEST_CHANNEL`, `RSVC_TEST_PASSWORD`, `RSVC_TEST_OTHER_CHANNEL` and
`RSVC_TEST_OTHER_PASSWORD` environment variables override those test credentials.
It is not evidence of microphone quality,
real-game lifecycle behavior, internet latency, or production Cloudflare availability.

Follow the [manual acceptance checklist](docs/testing.md) with two consenting players.
See [Plugin Hub review notes](docs/plugin-hub.md) before submitting.

## Bandwidth and microphone tests

Open **Stats / test** in the sidebar. **Reset stats** begins a new measurement;
**Save stats CSV** exports counts, durations, average rates and per-state bytes.
Measure 30 seconds each of connected idle, speaking with push-to-talk held, and
silence with push-to-talk held. “Audio” means encoded activity, including noise
and encoder warm-up; it is not a reliable speech/transcript detector. “DTX silence”
means the encoder produced a suppressed frame. Idle listeners can still receive
other speakers' audio. No names, channel/passwords or coordinates are exported.

The counters separate audio/control and upload/download. Sent message bytes count
messages accepted by OkHttp, not confirmed delivery. Estimated WebSocket bytes
include frame headers/masking only; neither total includes TLS/TCP/IP, ACKs,
retransmissions, handshake or ping/pong traffic. Provider billing and packet-capture
totals will differ. Suppression savings compare against sending the same tiny DTX
packets, not against a second no-DTX encoder running secretly in the background.

Position updates are removed. A small active-login heartbeat is sent about every
five seconds and immediately when login eligibility changes, with a small reply.
Participant lists are sent on joins/leaves, and unchanged mute/deafen lists are
not repeatedly sent. Idle connections still use heartbeat traffic to detect outages.

For a live baseline, enable **Test baseline: no DTX** in settings, rejoin, reset
statistics and repeat the same timed test. This keeps 16 kbps and 20 ms framing
but sends every captured frame. Changing the profile resets counters on joining.
Turn the baseline setting off and rejoin afterward.

For an exact same-input comparison, save your own microphone WAV using the explicit
controls above, then run this development-only tool with JDK 11:

```powershell
.\tools\bandwidth-audit.ps1 -InputAudio 'C:\path\to\voice-test.wav'
```

It accepts the exported 48 kHz, 16-bit mono WAV or the reference raw fixture, tests
baseline/DTX/suppression offline and writes CSV under `build/bandwidth-audit/`.
It never opens the mic or uploads the recording. Six seconds of generated silence
are appended for comparison. Other bitrate rows are experiments only; production
remains 16 kbps. The complete decoder and vendored source/license hashes are unchanged.

## Repository layout

- `src/main/java/com/rsvc/`: RuneLite plugin, audio, protocol, UI and reconnection.
- `src/main/java/org/concentus/`: 117 vendored codec source files, with original notices.
- `third_party/concentus/`: license, source provenance and local modification record.
- `relay/src/core.js`: transport-independent routing and validation.
- `relay/src/channels.js`: required channel configuration and handshake authentication.
- `relay/src/worker.js`: Cloudflare Worker/Durable Object adapter.
- `relay/src/node.js`: portable Node WebSocket adapter (loopback by default).
- `gradle/verification-metadata.xml`: dependency hashes; `relay/package-lock.json`: npm lock.

The codec is a trimmed source copy of original Concentus; no additional Maven
runtime dependency is required. Seven unused wrapper/resampler files are omitted,
and two console diagnostics are removed. `verifyVendoredCodec` checks the committed
source/license hashes before compilation. Its license and per-file notices are
bundled as `META-INF/CONCENTUS-LICENSE.txt` and `META-INF/CONCENTUS-NOTICES.txt`.
RS VC code is BSD-2-Clause; the vendored codec retains its own license and attribution.
