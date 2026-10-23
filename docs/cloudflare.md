# Hosting and connection lifecycle

## What is free

As checked on October 23, 2026, Workers Free supports SQLite-backed Durable Objects.
Durable Objects include 100,000 request units/day and 13,000 GB-seconds/day; a 20:1
billing adjustment applies to incoming WebSocket messages. Outgoing WebSocket
messages have no request charge. Free-tier operations fail when limits are exhausted.
Other apps in the account share those allowances. Consult the live pricing page
before deploying: quotas, metering and terms can change.

Sources:
- https://developers.cloudflare.com/durable-objects/platform/pricing/
- https://developers.cloudflare.com/workers/platform/pricing/
- https://developers.cloudflare.com/durable-objects/platform/limits/

Twenty-millisecond audio produces 50 incoming messages per speaking second.
At the documented billing ratio, one continuous speaking hour represents about
9,000 request units **before** heartbeat, control, connection and other traffic.
This arithmetic is a cost-sizing aid, not a guaranteed free-tier capacity claim.
Measure quota counters in a real beta, including duration and CPU. A room at the
64-user safety ceiling is not promised to run all day on Free.

There is only one bounded Durable Object, regardless of world or user input.
No user can allocate an unlimited number of objects by choosing arbitrary rooms.
It keeps no database of audio, receives no locations, uses hibernation-compatible sockets,
and has no periodic Durable Object timer keeping idle sessions awake. A server-side
session-idle timeout is evaluated when handling joins/messages; disconnected and
silent clients are evicted before new connections are admitted.

Use push-to-talk, private channel passwords, and provider metrics.
The server rejects oversized messages, excess audio/control rates and full rooms.
These are workload guards, not a promise against distributed quota exhaustion.

## Disconnects

Normal Durable Object hibernation preserves connections; attachments reconstruct
current peer state when the object wakes. Deploying updated object code disconnects
all sockets. Runtime updates, resource pressure, network changes and free-quota
exhaustion may also disconnect clients. This is why a continuous session cannot
depend on an immortal TCP connection.

Sources:
- https://developers.cloudflare.com/durable-objects/best-practices/websockets/
- https://developers.cloudflare.com/workers/platform/limits/#duration

The plugin drops queued audio on disconnect, closes the mic, and makes at most six
reconnect attempts with 1, 2, 4, 8, 16 and 30 second delays plus small random jitter.
After 30 stable seconds the retry budget resets. Authentication failures and
protocol-policy rejection require an explicit rejoin. Logging out, leaving voice,
or changing settings cancels pending recovery. A new press of push-to-talk is needed
to transmit again; a held key must first be released. Heartbeats run about every
five seconds; missing replies for 15 seconds detect an unresponsive relay.
Heartbeats carry only the self-reported active-login flag. Participant rosters
are sent on membership changes, not on every heartbeat. Loading/world hops pause
audio until logged in again without requiring the same world or location.

Audio during an outage is lost, not replayed. Socket.IO could provide similar
reconnect machinery, but does not remove server-side disconnects or TCP ordering.
Workers/Durable Objects do not provide a raw UDP listening socket. The protocol
core is separated from its adapters so another host/transport can be evaluated
without rewriting channel routing rules. UDP is not treated as categorically banned
by Plugin Hub; see the review notes.

## Portable hosting

The same Java client works with the Node adapter. Run Node 22+, `npm ci --omit=dev`,
and `npm start`. It listens on loopback by default, suitable for a TLS reverse proxy.
Set `PORT`, `HOST` and the required `CHANNELS_JSON` map as environment variables. Expose a
trusted TLS endpoint at `/v1/voice` and forward WebSocket upgrades. `/health` reports
only service/protocol status. The client refuses unencrypted non-loopback endpoints.

Both adapters authenticate channel name and password before upgrading a socket.
No configured channels means no voice connections. Use `wrangler secret put
CHANNELS_JSON` for Cloudflare; `.dev.vars` is only for its local emulator. The
map contains at most 32 channel names (3–32 lowercase letters, digits, `_`, `-`)
and passwords (6–128 printable ASCII characters, no spaces). Generate separate
random voice passwords. The global 64-connection cap applies across all channels.
There is no public fallback or client-created channel. The old optional
`ACCESS_TOKEN` setting no longer enables access. See README for local fixtures.
Deploy plugin and relay protocol version 2 together; `/v1/voice` stays the endpoint.
Legacy position messages and protocol-1 clients/attachments are rejected. The
plugin enforces logged-in use locally; the relay cannot verify RuneScape login.

No DNS, Cloudflare account, subscription, or hosting configuration is changed by
building/testing this repository. `npm run deploy` is the publishing step.
