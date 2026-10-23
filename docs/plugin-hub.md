# Plugin Hub review notes

This repository targets reviewable Plugin Hub code; it does not claim acceptance.
The current template guidelines and reviewer decisions remain authoritative:

- https://github.com/runelite/plugin-hub-tooling/blob/master/templateplugin/AGENTS.md
- https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features
- https://github.com/runelite/plugin-hub

## Implementation choices

- Java 11 bytecode, pure Java codec, no JNI/JNA, external processes, reflection,
  dynamically loaded code or changes to game input/actions.
- User-requested CSV and own-mic WAV exports use `Filepath.Chooser` and `Filepath`
  with `internalName = "rs-vc"`; writes run on a dedicated worker. No automatic
  recording or disk export. Recording is a bounded 60-second raw-mic clip armed
  in the diagnostics dialog; received audio is excluded. Shutdown stops timers,
  discards the unsaved clip and shuts down the export worker without waiting.
- HTTP/WebSocket transport uses injected RuneLite `OkHttpClient`; JSON uses injected Gson.
- The mic and codec run off the game/Swing threads; blocking device cleanup is
  dispatched to a dedicated worker. Shutdown cancels network work and closes devices.
- Game APIs are sampled on the client thread. Worker callbacks return there before
  reading/modifying client-related state.
- Consent and the plugin itself default off; joining is explicit. The config
  warning uses the exact IP-disclosure text from the local AGENTS.md; the setting's
  description also discloses audio/name/IP and active-login sharing with the relay.
- No positions, worlds, planes or instance identifiers are collected or sent.
  Client-thread login checks gate audio; a five-second heartbeat carries the
  active-login flag. Channel membership alone determines who can hear whom,
  subject to mute/ignore/deafen, login freshness and the four-speaker cap.
- Configurable endpoints require TLS, apart from loopback testing. Private-channel
  passwords are required, entered in memory, never written to profile files or
  logged, and cleared when leaving. These are separate voice secrets, not account
  credentials. The relay owner configures channels; no public channel is available.
- Audio is free to use; no significant feature is gated by a paid external service.
- Account names and login flags are self-reported; channel passwords authenticate
  channel access, not RuneScape accounts. Passwords accept 6–128 non-space ASCII characters.

## UDP clarification

The template AGENTS.md requires OkHttp for **HTTP requests**. The public
`disallowed-apis.txt` does not list `DatagramSocket` or `DatagramChannel`. The wiki
uses broader language about network clients, so do not present either automatic
acceptance or an explicit universal UDP ban as established fact. Standard Java
UDP could be proposed for review. This implementation uses WebSockets because
they fit the requested Cloudflare Free Worker/Durable Object host.

https://github.com/runelite/plugin-hub-tooling/blob/master/package/src/main/resources/net/runelite/pluginhub/packager/disallowed-apis.txt

## Vendored codec

Production now compiles 117 original Concentus source files from
`src/main/java/org/concentus`. The Maven codec dependency has been removed.
Encoding is 16 kbps VBR mono, voice signal hint, complexity 4, with 48 kHz input and
20 ms frames. DTX is enabled, with packets of at most two bytes omitted from the
transport. Sequence numbers advance through omissions; playback clears queued old
audio and resets decoder state after a gap, with bounded rebuffering. The complete
single-stream decoder remains intact. There is no PCM amplitude gate.

Original upstream: https://github.com/lostromb/concentus
License: https://github.com/lostromb/concentus/blob/master/LICENSE

`third_party/concentus/provenance.json` records original and imported SHA-256 hashes,
the seven omitted files, and the sole local codec patch: removing two console
diagnostics and their temporary variables in Bands.java. The user-provided archive
has no Git revision; no commit identifier has been invented. Individual copyright
headers remain in the source, and both the general license and collected original
file notices are shipped in META-INF resources. The reference archive is not needed
to build or run the plugin. `verifyVendoredCodec` validates hashes before compilation.

The previous Maven dependency is only an offline interoperability reference for
the optional audit tool, never a production or test runtime dependency:

```text
io.github.jaredmdobson:concentus:1.0.2
concentus-1.0.2.jar
3584da8a1ac7e0814a03ce3c1b1e70250bf7b07668bc39eea1048796d6471ce3
```

The imported source was scanned for native loading, reflection, process execution,
networking and file IO. This is not a complete independent codec security audit.
Vendoring does not bypass Plugin Hub review: reviewers must assess the imported
source, license, microphone/privacy behavior and local modifications.

`build=gradle` builds the plugin and codec sources together. The ordinary JAR includes
their compiled classes and notices, but is not a standalone RuneLite installation.

Local dependency hashes use the resolved RuneLite release at build time. When
`latest.release` changes, review the upstream change before deliberately updating
verification metadata. Do not automatically bless failed verification in CI.

### Copying or maintaining the codec ourselves

The upstream license permits source/binary redistribution and modification,
subject to retaining the applicable copyright notices, conditions and disclaimer,
and its restriction on endorsement using contributor/organization names. There
is no line-count allowance: whole files or the whole implementation can be
vendored under those terms. Copied code remains third-party code even when built
inside this project, and still needs review and maintenance.

The original `concentus-master/` archive replaces the earlier decompiled reference.
Use its original source headers if adapting it. Its Java package is `org.concentus`,
not the Maven fork's package. The archive README describes a libopus 1.1.2-based
implementation; the LICENSE's reference to bundled C 1.5.2 binaries does not mean
the Java implementation is based on 1.5.2. Do not copy native binaries, the test
console, other language ports, or audio fixtures into the RuneLite plugin.
The reference trees are ignored by Git and outside Gradle's production source sets.

### Measured mono subset — October 23, 2026

`tools/codec-audit.ps1` compiles the original Java source and a conservative source
dependency closure rooted at OpusEncoder/OpusDecoder. It copies whole source files,
preserving their headers and LICENSE; no codec methods are changed. Run from the
project root with `JAVA_HOME` set to JDK 11:

```powershell
.\tools\codec-audit.ps1
```

It requires the local reference archive and the previously verified Maven JAR at
`.tools/concentus.jar` (or supply `-PublishedJar`). Every run creates a fresh ignored
`.tools/codec-audit/<id>/` directory containing source candidates, JARs, measurements,
fixture/source hashes and results. The harness is development-only; its file IO
and output are not included in the plugin.

| Measurement | Original Java codec | Compiling subset |
| --- | ---: | ---: |
| Source files | 124 | 117 |
| Source bytes, including comments/licenses | 1,709,126 | 1,606,451 |
| Nonblank lines, including comments/licenses | 33,921 | 31,647 |
| Compiled classes | 136 | 130 |
| Compressed JAR bytes, including LICENSE | 354,185 | 340,045 |

This removes 6.0% of source bytes and 4.0% of the JAR. The seven omitted files are
ChannelLayout, OpusMSDecoder, OpusMSEncoder, OpusMultistream, SpeexResampler,
TOCStruct and VorbisLayout. The standalone SpeexResampler is unused; the codec's
internal Resampler remains required. This is safe whole-file pruning, not proof
of a mathematically minimal mono implementation. Mono branches within remaining
files have not been specialized.

All 130 retained classes matched the original build byte-for-byte. Six profiles
produced identical packet/decoded-PCM digests in the original and subset builds.
Both builds cross-decoded with the pinned Maven dependency, including the reverse
direction at the previous 24 kbps profile. This checks the bundled fixture, not
full Opus conformance, hostile-input robustness, or subjective speech quality.
After integration and diagnostic removal, the production codec also matched all
six packet/PCM digests and passed the same cross-decoding check. Normal Java tests
add coverage for the 16 kbps profile and transitions between all decoder modes.

The original source had two stray `System.out.println` diagnostics in Bands.java;
the production import removes them, with the patch recorded in provenance.
Per-file notices and source hashes are preserved. The code remains third-party
source even without a Maven dependency. No native APIs, reflection, networking or file IO were found by the
targeted Java-codec source scan; that is not a complete security audit.

### Bandwidth is a separate change

Measured Opus payload rates on the archive's `48Khz Mono.raw` fixture (873 frames,
17.46 seconds), followed by six seconds of digital silence. All profiles use mono,
48 kHz input, 20 ms frames and complexity 4. `voice` sets the voice signal hint.

| Profile | Fixture kbps | Appended silence kbps |
| --- | ---: | ---: |
| Previous 24 kbps | 23.578 | 12.856 |
| Voice 20 kbps | 19.804 | 10.564 |
| Voice 16 kbps | 15.412 | 5.243 |
| Voice 16 kbps + DTX | 15.186 | 0.773 |

These exclude our protocol header, WebSocket/TLS/TCP/IP overhead and control
traffic; VBR results depend on content. At nominal 16 rather than 24 kbps, Opus
payload is reduced by one third, from 10.8 to 7.2 MB per continuously encoded hour.
Keeping 50 audio messages per second does not reduce Durable Object message usage.
DTX returned tiny packets for 278 of 300 silence frames in the original experiment.
The current transport now omits these frames and accounts for sequence gaps in
playback; the full decoder is unchanged. The updated BandwidthAudit measures both
DTX alone and DTX plus suppression, including estimated WebSocket framing.

The 24/20 kbps fixture profiles used hybrid SILK+CELT. The 16 kbps profiles used
SILK. Setting a wideband cap or forcing SILK at 16 kbps produced the same output
on this fixture, but this does not prove all inputs behave identically. Mono alone
does not make CELT removable. A SILK-only fork would require changing initialization,
mode transitions, redundancy and shared helpers, and explicitly rejecting unsupported
packets or retaining a complete decoder. It would not decode the current hybrid
packets if its decoder also discarded CELT. RFC 6716 describes these codec modes:
https://www.rfc-editor.org/rfc/rfc6716

Selected implementation: 16 kbps mono with the voice signal hint, full decoder,
20 ms framing and the seven-file omission. Codec mathematics are unchanged. DTX
and silence suppression are enabled. Real microphone/speech quality and in-game behavior still need
the manual tests; the measurements above are offline payload measurements only.

## Submission preparation

1. Complete the manual two-client tests and document results.
2. Publish the source repository and identify its actual commit. Do not put
   channel passwords, local build caches or `.dev.vars` in Git.
3. Request review of the microphone/private-channel design and vendored Concentus source.
4. In a fork of `runelite/plugin-hub`, add a manifest for the actual repository/commit
   with an appropriate warning. No submission manifest here fabricates a repository
   URL or commit hash.

Suggested warning:

```text
This plugin submits your IP address, RS name, active-login flag and live microphone audio to a third-party server not controlled or verified by RuneLite developers.
```

No Plugin Hub PR has been opened, and no reviewer approval has been obtained.
