/**
 * Voice session coordination. RuneLite supplies plain game-presence/position inputs and handles
 * UI, device and party-service effects; this package owns connection identity, retry timing,
 * saved room recovery and audio eligibility. The network package owns the wire protocol.
 * Session commands are owner-thread-only, with no scheduler or hidden background work.
 */
package com.rsvc.client;
