# Optional Cloudflare Realtime SFU direction

Status: researched, **not implemented**. The working implementation remains pure
Java Opus over WebSockets. Changing this architecture needs an explicit decision.

## Direct SFU media

Cloudflare SFU normally uses WebRTC for endpoint media. The plugin would need a
reviewable Java WebRTC implementation, including signaling/SDP, ICE/STUN/TURN,
DTLS-SRTP, RTP/RTCP and loss/congestion handling. Concentus only supplies Opus.
Many common Java WebRTC bindings use JNI, which does not meet the chosen review
requirements. No suitable complete pure-Java stack has been validated here.

Standard Java UDP is not explicitly listed as banned in the template guidelines
or the disallowed API file. A direct implementation must still pass review; do not
infer guaranteed approval from that absence. It would also need tested secure
transport, NAT traversal and congestion control, not just Opus datagrams.

Cloudflare credentials stay on a backend. The backend authorizes private-channel
subscriptions; putting the SFU App Secret inside the plugin is not acceptable.

## WebSocket adapters

Current Cloudflare documentation says adapters are generally available. Each
adapter connects one media track in one direction. The SFU connects **outward** to
a public WSS service endpoint; a RuneLite client behind NAT is not that endpoint.

Audio input/output is 48 kHz stereo signed 16-bit little-endian PCM, framed in
protobuf. Directly feeding our compressed Opus payloads would not work. Options:

- Carry PCM from RuneLite through a hosted bridge to the adapter, and reverse it
  for reception. PCM is 1,536,000 bit/s, or 691.2 MB per continuously streamed hour
  before overhead. This is about 96 times our 16 kbit/s Opus target payload bandwidth.
- Keep Opus on the RuneLite WebSocket leg, but add decode/encode work at the bridge.
  That adds compute, state, cost and codec/lifecycle complexity.

These routes retain a WebSocket bridge and do not eliminate its disconnects. The
SFU adapter's egress recovery is bounded; ingestion needs recreation after a
terminal disconnect. Additional allocations also need explicit cleanup and
partial-success handling. The SFU is most compelling if endpoints can use WebRTC
directly, or interoperability with browser/native WebRTC clients becomes a goal.

Sources checked October 23, 2026:
- https://developers.cloudflare.com/realtime/sfu/
- https://developers.cloudflare.com/realtime/sfu/features/media-transport-adapters/websocket-adapter/
- https://developers.cloudflare.com/realtime/sfu/api/
- https://developers.cloudflare.com/realtime/sfu/platform/pricing/
