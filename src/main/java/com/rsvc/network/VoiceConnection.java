package com.rsvc.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rsvc.VoiceObserver;
import com.rsvc.proximity.VoiceAlias;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import okio.ByteString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * WebSocket protocol and routing state. Synchronized entry points serialize OkHttp callbacks with
 * game/audio-thread calls; volatile snapshots support read-only access without taking the monitor.
 * Callbacks run on the invoking thread and must dispatch game/UI work to the appropriate thread.
 */
public final class VoiceConnection extends WebSocketListener implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(VoiceConnection.class);
    public static final class Peer {
        public final long id;
        public final String name;
        public final boolean muted, deaf;

        public Peer(long id, String name) {
            this(id, name, false, false);
        }

        public Peer(long id, String name, boolean muted, boolean deaf) {
            this.id = id;
            this.name = name;
            this.muted = muted;
            this.deaf = deaf;
        }
    }

    private final Gson gson;
    private final Consumer<String> status;
    private final Consumer<List<Peer>> roster;
    private final Consumer<VoicePacket> receiver;
    private final Consumer<DisconnectReason> ended;
    private final String name;
    private volatile VoiceObserver observer;
    private long heartbeatSentAt;
    private volatile long heartbeatAckAt;
    private volatile boolean active;
    private boolean proximitySuspended;
    private String lastState = "";
    // What the relay holds for near and seen, so states can carry only the changes. A new
    // connection starts empty on both sides; a key change resends both lists in full,
    // because the relay drops states it receives under the old key.
    private Set<Long> sentNear = Collections.emptySet();
    private Set<String> sentSeen = Collections.emptySet();
    private boolean resendLists = true;
    private String token = "";
    private volatile String salt = "";
    public static final int MAX_SEEN = 255;
    // Introduced peers by matching token; replaced whole on each change.
    private volatile Map<String, Long> introduced = Collections.emptyMap();
    private final PeerIntroductions introductions = new PeerIntroductions();
    private int protocol;
    private volatile int frameMillis = 20;
    private long keyEpoch;
    private boolean refreshingKey;
    private boolean roomsSupported, roomPending;
    private boolean proximityRoomsSupported;
    private String region = "";
    private boolean peerStatusSupported;
    private boolean selfMuted, selfDeaf;
    private boolean peerStatusDirty = true;
    private String lastPeerStatus = "";
    private long peerStatusSentAt;
    private volatile String roomCode = "";
    private String resumeRoom = "";
    private String restoringRoom = "";
    private String recoveryRoom = "";
    private boolean failedRoomRecovery;
    private String requiredRoom;
    private Consumer<RoomState> roomListener = state -> {};

    private Set<Long> nearby = Collections.emptySet();
    // Fingerprints of visible characters (first five hex digits of their tokens), for mutual
    // discovery. Full tokens of other players never leave the client.
    private Set<String> seen = Collections.emptySet();
    private long revision;
    private long acknowledgedRevision = -1;
    private volatile long selfId;
    private int listeners;
    private volatile WebSocket socket;
    private volatile boolean open;
    private volatile boolean closed;
    private volatile boolean deaf;
    private volatile long activityAt;
    private volatile long openedAt;
    private volatile List<Peer> peers = Collections.emptyList();
    private volatile Set<Long> blocked = Collections.emptySet();
    private Set<String> mutedAliases = Collections.emptySet();

    // Optional UDP audio path; the WebSocket stays the control channel and audio fallback.
    static final long UDP_PROBE_INTERVAL = 250_000_000L, UDP_PROBE_WINDOW = 2_500_000_000L;
    static final long UDP_KEEPALIVE = 5_000_000_000L, UDP_TIMEOUT = 12_000_000_000L;
    static final long UDP_RETRY = 30_000_000_000L;
    private URI endpoint;
    private boolean udpWanted;
    private UdpAudio udp;
    private volatile boolean udpActive;
    // System.nanoTime() values; 0 means unset.
    private long udpProbeUntil, udpProbedAt, udpAckAt, udpRetryAt;

    public static final class RoomState {
        public final String code, reason;
        public final long expiresAt;
        public final boolean restorationFailed;

        RoomState(String code, String reason, long expiresAt, boolean restorationFailed) {
            this.code = code;
            this.reason = reason;
            this.expiresAt = expiresAt;
            this.restorationFailed = restorationFailed;
        }
    }

    public synchronized void roomListener(Consumer<RoomState> listener, String resume) {
        roomListener(listener, resume, false);
    }

    public synchronized void roomListener(Consumer<RoomState> listener, String resume, boolean paused) {
        roomListener = listener;
        recoveryRoom = resume;
        failedRoomRecovery = paused && !resume.isEmpty();
        resumeRoom = failedRoomRecovery ? "" : resume;
        restoringRoom = resumeRoom;
    }

    public boolean inRoom() {
        return !roomCode.isEmpty();
    }

    public String roomCode() {
        return roomCode;
    }

    /** Null is ordinary voice; empty blocks all audio while party discovery is in progress. */
    public synchronized void requireRoom(String code) {
        requiredRoom = code;
        sendState();
    }

    private boolean scopeAllowed() {
        return restoringRoom.isEmpty()
                && !failedRoomRecovery
                && (requiredRoom == null
                        || (!requiredRoom.isEmpty() && requiredRoom.equals(roomCode)));
    }

    public synchronized boolean roomRecoveryPaused() {
        return failedRoomRecovery;
    }

    public synchronized boolean awaitingRoomRecovery() {
        return failedRoomRecovery || !restoringRoom.isEmpty();
    }

    /** Kept in memory only, including across a failed retry. */
    public synchronized String recoveryRoomCode() {
        return recoveryRoom;
    }

    public synchronized boolean retryRoomRecovery() {
        if (!failedRoomRecovery || recoveryRoom.isEmpty() || !roomsAvailable()) {
            return false;
        }
        restoringRoom = recoveryRoom;
        failedRoomRecovery = false;
        requestRoom("join", recoveryRoom, true);
        return true;
    }

    public synchronized boolean returnToNearbyVoice() {
        if (!failedRoomRecovery || roomPending || !roomCode.isEmpty()) {
            return false;
        }
        recoveryRoom = "";
        failedRoomRecovery = false;
        sendState();
        return true;
    }

    public synchronized boolean roomsAvailable() {
        return roomsSupported && activeConnection() && !roomPending;
    }

    public synchronized void room(String action, String code) {
        requestRoom(action, code, false);
    }

    private void requestRoom(String action, String code, boolean automatic) {
        if (!roomsAvailable()) {
            if (!automatic) {
                status.accept("Voice isn't connected yet — try the room again");
            }
            return;
        }
        if (!action.equals("create") && !action.equals("join") && !action.equals("leave")) {
            throw new IllegalArgumentException();
        }
        String normalized =
                code.replace("-", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        if (action.equals("join") && !normalized.matches("[0-9a-f]{24}")) {
            status.accept("Enter a valid 24-character invite code");
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "room");
        msg.addProperty("action", action);
        if (action.equals("join")) {
            msg.addProperty("code", normalized);
        }
        roomPending = true;
        if (!automatic) {
            resumeRoom = "";
            restoringRoom = "";
            recoveryRoom = "";
            failedRoomRecovery = false;
        }
        listeners = 0;
        peers = Collections.emptyList();
        roster.accept(peers);
        sendState();
        send(msg);
    }

    public VoiceConnection(
            Gson gson,
            String name,
            Consumer<String> status,
            Consumer<List<Peer>> roster,
            Consumer<VoicePacket> receiver,
            Consumer<DisconnectReason> ended) {
        this(gson, name, status, roster, receiver, ended, VoiceObserver.NONE);
    }

    public VoiceConnection(
            Gson gson,
            String name,
            Consumer<String> status,
            Consumer<List<Peer>> roster,
            Consumer<VoicePacket> receiver,
            Consumer<DisconnectReason> ended,
            VoiceObserver observer) {
        if (!VoiceAlias.valid(name)) {
            throw new IllegalArgumentException("Use a generated voice alias");
        }
        this.gson = gson;
        this.name = name;
        this.status = status;
        this.roster = roster;
        this.receiver = receiver;
        this.ended = ended;
        this.observer = observer;
    }

    public synchronized void observer(VoiceObserver next) {
        if (observer != next) {
            observer = next;
            observer.connected(open && !closed);
        }
    }

    public boolean isOpen() {
        return open && !closed;
    }

    public static URI validateEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        boolean local =
                "127.0.0.1".equals(host)
                        || "[::1]".equals(host)
                        || "::1".equals(host)
                        || "localhost".equals(host);
        if (host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !("wss".equals(uri.getScheme()) || (local && "ws".equals(uri.getScheme())))
                || !"/v1/voice".equals(uri.getPath())) {
            throw new IllegalArgumentException(
                    "Use wss://host/v1/voice (ws:// allowed only on loopback)");
        }
        return uri;
    }

    /**
     * Audio moves to UDP on its own when the relay offers it and the relay is reached without a
     * proxy, and returns to the WebSocket whenever UDP stops answering.
     */
    public void connect(OkHttpClient client, String endpoint) {
        connect(client, endpoint, true);
    }

    /** With udp false, audio always stays on the WebSocket. */
    public void connect(OkHttpClient client, String endpoint, boolean udp) {
        URI uri = validateEndpoint(endpoint);
        udpEndpoint(uri, udp && direct(client, uri));
        socket = client.newWebSocket(new Request.Builder().url(endpoint).build(), this);
    }

    synchronized void udpEndpoint(URI uri, boolean wanted) {
        endpoint = uri;
        udpWanted = wanted;
    }

    /** UDP would bypass RuneLite's proxy, so it is used only when the relay is reached directly. */
    static boolean direct(OkHttpClient client, URI endpoint) {
        try {
            Proxy proxy = client.proxy();
            if (proxy != null) {
                return proxy.type() == Proxy.Type.DIRECT;
            }
            int port = endpoint.getPort() != -1 ? endpoint.getPort() : 443;
            for (String scheme : new String[] {"https", "socket"}) {
                URI target = new URI(scheme, null, endpoint.getHost(), port, null, null, null);
                for (Proxy selected : client.proxySelector().select(target)) {
                    if (selected.type() != Proxy.Type.DIRECT) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException | URISyntaxException e) {
            return false;
        }
    }

    /** True while audio is sent and received over UDP rather than the WebSocket. */
    public boolean usingUdp() {
        return udpActive;
    }

    private void offerUdp(JsonObject offer) {
        long port = ControlJson.number(offer, "port", 1, 65535);
        String session = offer.get("session").getAsString(), key = offer.get("key").getAsString();
        if (!session.matches("[0-9a-f]{16}") || !key.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException();
        }
        if (!udpWanted || endpoint == null || udp != null) {
            return;
        }
        try {
            String host = endpoint.getHost().replace("[", "").replace("]", "");
            udp = new UdpAudio(
                    new InetSocketAddress(InetAddress.getByName(host), (int) port),
                    hex(session),
                    hex(key),
                    new UdpAudio.Listener() {
                        @Override
                        public void acknowledged() {
                            udpAcknowledged(System.nanoTime());
                        }

                        @Override
                        public void audio(byte[] payload) {
                            observer.message(false, true, payload.length);
                            receiveAudio(payload);
                        }
                    });
            udpProbeUntil = System.nanoTime() + UDP_PROBE_WINDOW;
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            log.debug("RSVC UDP unavailable: {}", e.getClass().getSimpleName());
        }
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    /** Probes until the relay answers, keeps the path alive, and falls back when it stops. */
    synchronized void udpTick(long now) {
        if (udp == null || closed) {
            return;
        }
        if (udpActive) {
            if (now - udpAckAt > UDP_TIMEOUT) {
                log.debug("RSVC UDP audio stopped; using the WebSocket");
                udpActive = false;
                udpRetryAt = now + UDP_RETRY;
                sendTransport(false);
            } else if (now - udpProbedAt >= UDP_KEEPALIVE) {
                probe(now);
            }
            return;
        }
        if (udpProbeUntil == 0 && udpRetryAt != 0 && now - udpRetryAt >= 0) {
            udpProbeUntil = now + UDP_PROBE_WINDOW;
            udpRetryAt = 0;
        }
        if (udpProbeUntil == 0) {
            return;
        }
        if (now - udpProbeUntil > 0) {
            udpProbeUntil = 0;
            udpRetryAt = now + UDP_RETRY;
        } else if (udpProbedAt == 0 || now - udpProbedAt >= UDP_PROBE_INTERVAL) {
            probe(now);
        }
    }

    private void probe(long now) {
        udpProbedAt = now;
        udp.send(UdpAudio.PROBE, new byte[0]);
    }

    synchronized void udpAcknowledged(long now) {
        if (closed || udp == null) {
            return;
        }
        udpAckAt = now;
        if (!udpActive) {
            log.debug("RSVC UDP audio path confirmed");
            udpActive = true;
            udpProbeUntil = 0;
            udpRetryAt = 0;
            sendTransport(true);
        }
    }

    private void sendTransport(boolean useUdp) {
        sendText("{\"type\":\"transport\",\"udp\":" + useUdp + "}");
    }

    @Override
    public synchronized void onOpen(WebSocket webSocket, Response response) {
        if (closed) {
            webSocket.cancel();
            return;
        }
        socket = webSocket;
        open = true;
        observer.connected(true);
        openedAt = heartbeatAckAt = System.nanoTime();
        status.accept("Connected — finding nearby voices");
    }

    @Override
    public synchronized void onMessage(WebSocket webSocket, String text) {
        if (closed) {
            return;
        }
        if (observer != VoiceObserver.NONE) {
            observer.message(
                    false, false, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        if (text.length() > 65536) {
            fail("Relay sent oversized control data", false);
            return;
        }
        try {
            JsonObject msg = gson.fromJson(text, JsonObject.class);
            String type = msg.get("type").getAsString();
            if ("welcome".equals(type)) {
                int version = (int) ControlJson.number(msg, "v", 8, 8);
                String key = msg.get("salt").getAsString();
                if (!key.matches("[0-9a-f]{64}") || !salt.isEmpty()) {
                    throw new IllegalArgumentException();
                }
                keyEpoch =
                        msg.has("keyEpoch")
                                ? ControlJson.number(msg, "keyEpoch", 1, 9007199254740991L)
                                : 0;
                if (msg.has("id")) {
                    selfId = ControlJson.number(msg, "id", 1, 0xffffffffL);
                }
                if (msg.has("frameMs")) {
                    long frame = ControlJson.number(msg, "frameMs", 20, 40);
                    if (frame != 20 && frame != 40) {
                        throw new IllegalArgumentException();
                    }
                    frameMillis = (int) frame;
                }
                protocol = version;
                salt = key;
                roomsSupported = msg.has("rooms") && msg.get("rooms").getAsBoolean();
                peerStatusSupported = optionalBoolean(msg, "peerStatus");
                proximityRoomsSupported = optionalBoolean(msg, "proximityRooms");
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "hello");
                hello.addProperty("v", protocol);
                hello.addProperty("alias", name);
                if (keyEpoch != 0) {
                    hello.addProperty("keyRotation", true);
                }
                send(hello);
                if (msg.has("udp")) {
                    offerUdp(msg.getAsJsonObject("udp"));
                }
                return;
            }
            if (protocol == 0) {
                throw new IllegalArgumentException();
            }
            if ("matching_key".equals(type)) {
                long epoch =
                        ControlJson.number(msg, "keyEpoch", keyEpoch + 1, 9007199254740991L);
                String key = msg.get("salt").getAsString();
                if (keyEpoch == 0 || !key.matches("[0-9a-f]{64}") || key.equals(salt)) {
                    throw new IllegalArgumentException();
                }
                keyEpoch = epoch;
                refreshingKey = true;
                resendLists = true;
                salt = key;
                // Existing routing/audio stays valid; only fresh local matching may send state.
                return;
            }
            if ("room".equals(type)) {
                if (!roomsSupported) {
                    throw new IllegalArgumentException();
                }
                String code = msg.get("code").getAsString(),
                        reason = msg.get("reason").getAsString();
                long expiresAt = ControlJson.number(msg, "expiresAt", 0, 9007199254740991L);
                if ((!code.isEmpty() && !code.matches("[0-9a-f]{24}"))
                        || !java.util.Arrays.asList(
                                        "created",
                                        "joined",
                                        "left",
                                        "updated",
                                        "expired",
                                        "full",
                                        "unavailable")
                                .contains(reason)) {
                    throw new IllegalArgumentException();
                }
                String previousRoom = roomCode;
                boolean changed = !previousRoom.equals(code);
                roomCode = code;
                if (changed) {
                    listeners = 0;
                    peers = Collections.emptyList();
                    acknowledgedRevision = -1;
                    roster.accept(peers);
                }
                boolean restorationFailed = false;
                if (!restoringRoom.isEmpty() && !"updated".equals(reason)) {
                    restorationFailed = !"joined".equals(reason) || !restoringRoom.equals(code);
                    failedRoomRecovery = restorationFailed;
                    restoringRoom = "";
                    if (!restorationFailed) {
                        recoveryRoom = "";
                    }
                } else if ("expired".equals(reason) && !previousRoom.isEmpty() && requiredRoom == null) {
                    recoveryRoom = previousRoom;
                    failedRoomRecovery = restorationFailed = true;
                }
                roomListener.accept(new RoomState(code, reason, expiresAt, restorationFailed));
                if ("expired".equals(reason)) {
                    JsonObject ack = new JsonObject();
                    ack.addProperty("type", "room");
                    ack.addProperty("action", "acknowledge");
                    send(ack);
                }
                if (!"updated".equals(reason)) {
                    roomPending = false;
                }
                sendState();
                return;
            }
            if ("heartbeat".equals(type)) {
                heartbeatAckAt = System.nanoTime();
                return;
            }
            if ("transport".equals(type)) {
                JsonElement value = msg.get("udp");
                if (udp == null || value == null || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException();
                }
                // The relay refused or dropped UDP: keep audio on the WebSocket and retry later.
                if (!value.getAsBoolean() && udpActive) {
                    udpActive = false;
                    udpRetryAt = System.nanoTime() + UDP_RETRY;
                }
                return;
            }
            if ("matches".equals(type)) {
                introductions.accept(msg);
                introduced = introductions.identities();
                return;
            }
            if (!"peers".equals(type)) {
                throw new IllegalArgumentException();
            }
            String scope = msg.has("scope") ? msg.get("scope").getAsString() : "";
            if (roomPending || !scope.equals(roomCode)) {
                return;
            }
            if (msg.get("rev").getAsLong() != revision) {
                return;
            }
            int audience = msg.get("listeners").getAsInt();
            JsonArray array = msg.getAsJsonArray("peers");
            // Rosters only name peers this client was introduced to.
            if (array.size() > PeerIntroductions.MAX_ENTRIES
                    || audience < 0
                    || audience > array.size()) {
                throw new IllegalArgumentException();
            }
            List<Peer> next = new ArrayList<>();
            Set<Long> peerIds = new HashSet<>();
            for (JsonElement item : array) {
                JsonObject p = item.getAsJsonObject();
                long id = p.get("id").getAsLong();
                String n = p.get("alias").getAsString();
                if (id < 1 || id > 0xffffffffL || !VoiceAlias.valid(n) || !peerIds.add(id)) {
                    throw new IllegalArgumentException();
                }
                if (inRoom() || nearby.contains(id)) {
                    next.add(new Peer(id, n, optionalBoolean(p, "muted"), optionalBoolean(p, "deaf")));
                }
            }
            acknowledgedRevision = revision;
            listeners = audience;
            peers = Collections.unmodifiableList(next);
            roster.accept(peers);
        } catch (RuntimeException e) {
            fail("Relay sent invalid control data", false);
        }
    }

    @Override
    public synchronized void onMessage(WebSocket webSocket, ByteString bytes) {
        if (closed) {
            return;
        }
        observer.message(false, true, bytes.size());
        receiveAudio(bytes.toByteArray());
    }

    private synchronized void receiveAudio(byte[] bytes) {
        if (closed) {
            return;
        }
        if (!canReceive()) {
            return;
        }
        try {
            VoicePacket packet = VoicePacket.downstream(bytes);
            for (Peer peer : peers) {
                if (peer.id == packet.speaker
                        && (inRoom() || nearby.contains(peer.id))
                        && !blocked.contains(peer.id)
                        && !mutedAliases.contains(peer.name)) {
                    receiver.accept(packet);
                    return;
                }
            }
        } catch (IllegalArgumentException ignored) {
            /* Malformed audio never reaches the decoder. */
        }
    }

    private boolean activeConnection() {
        return open
                && !closed
                && active
                && activityAt != 0
                && System.nanoTime() - activityAt < 2_500_000_000L;
    }

    private boolean canReceive() {
        return activeConnection()
                && (!proximitySuspended || inRoom())
                && !deaf && !roomPending && scopeAllowed();
    }

    public synchronized boolean canTransmit() {
        return canReceive()
                && acknowledgedRevision == revision
                && listeners > 0
                && (inRoom() || !nearby.isEmpty())
                && !peers.isEmpty();
    }

    public String matchingSalt() {
        return salt;
    }

    /** This connection's relay ID from welcome, or 0 before it arrives. */
    public long selfId() {
        return selfId;
    }

    /** True once the relay has acknowledged the latest state (registration accepted). */
    public synchronized boolean stateAcknowledged() {
        return revision > 0 && acknowledgedRevision == revision;
    }

    /** Opus packet duration the relay asked for in welcome: 20 (default) or 40 ms. */
    public int frameMillis() {
        return frameMillis;
    }

    /** Relay IDs of introduced peers, keyed by their matching tokens. */
    public Map<String, Long> introductions() {
        return introduced;
    }

    public synchronized void mutedAliases(Set<String> aliases) {
        mutedAliases = new HashSet<>(aliases);
    }

    public boolean isStable() {
        return open && !closed && System.nanoTime() - openedAt > 30_000_000_000L;
    }

    public void checkHealth() {
        if (open && !closed && System.nanoTime() - heartbeatAckAt > 15_000_000_000L) {
            fail("Relay stopped responding");
        }
    }

    /** Called only with locally matched consenting voice IDs, never arbitrary player hashes. */
    public synchronized void proximity(String matchingKey, String identity, Set<Long> ids) {
        proximity(matchingKey, identity, ids, "");
    }

    public synchronized void proximity(
            String matchingKey, String identity, Set<Long> ids, String regionKey) {
        proximity(matchingKey, identity, ids, regionKey, Collections.emptySet());
    }

    public synchronized void proximity(
            String matchingKey,
            String identity,
            Set<Long> ids,
            String regionKey,
            Set<String> fingerprints) {
        if (!matchingKey.equals(salt)) {
            return; // A key update raced local matching; retry with the new key next tick.
        }
        refreshingKey = false;
        if (!identity.isEmpty() && !identity.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid voice token");
        }
        if (!regionKey.isEmpty() && !regionKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid proximity region");
        }
        if (fingerprints.size() > MAX_SEEN
                || !fingerprints.stream().allMatch(f -> f.matches("[0-9a-f]{5}"))) {
            throw new IllegalArgumentException("Invalid sightings");
        }
        token = identity;
        proximitySuspended = false;
        region = identity.isEmpty() ? "" : regionKey;
        active = !identity.isEmpty();
        activityAt = System.nanoTime();
        nearby = active ? new HashSet<>(ids) : Collections.emptySet();
        seen = active ? new java.util.TreeSet<>(fingerprints) : Collections.emptySet();
        List<Peer> retained = new ArrayList<>();
        for (Peer peer : peers) {
            if (inRoom() || nearby.contains(peer.id)) {
                retained.add(peer);
            }
        }
        if (retained.size() != peers.size()) {
            peers = Collections.unmodifiableList(retained);
            roster.accept(peers);
        }
        sendState();
        if (!resumeRoom.isEmpty() && roomsAvailable()) {
            String code = resumeRoom;
            resumeRoom = "";
            requestRoom("join", code, true);
        }
    }

    /** Keep established room audio alive without claiming an old map position is current. */
    public synchronized boolean suspendProximity(String matchingKey, String identity) {
        boolean changed = !proximitySuspended || !region.isEmpty() || !nearby.isEmpty();
        // The client may refresh its established identity without reading a loading scene.
        // Never publish an old token under a new matching-key epoch.
        if (matchingKey.equals(salt)) {
            if (!identity.equals(token) && !identity.isEmpty()
                    && !identity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid voice token");
            }
            changed |= !identity.equals(token) || refreshingKey;
            token = identity;
            refreshingKey = false;
        }
        proximitySuspended = true;
        region = "";
        nearby = Collections.emptySet();
        seen = Collections.emptySet();
        active = inRoom() && !token.isEmpty();
        activityAt = System.nanoTime();
        if (!inRoom() && !peers.isEmpty()) {
            peers = Collections.emptyList();
            roster.accept(peers);
        }
        if (changed) {
            sendState();
        }
        return activeConnection() && inRoom() && !roomPending && scopeAllowed();
    }

    public synchronized void heartbeat() {
        if (!activeConnection() && active) {
            proximity(salt, "", Collections.emptySet());
        }
        long now = System.nanoTime();
        udpTick(now);
        sendPeerStatus(now);
        if (salt.isEmpty() || (heartbeatSentAt != 0 && now - heartbeatSentAt < 5_000_000_000L)) {
            return;
        }
        if (sendText("{\"type\":\"heartbeat\"}")) {
            heartbeatSentAt = now;
        }
    }

    /** Latest self-selected controls only; transmission is coalesced by heartbeat(). */
    public synchronized void selfStatus(boolean muted, boolean deaf) {
        peerStatusDirty |= selfMuted != muted || selfDeaf != deaf;
        selfMuted = muted;
        selfDeaf = deaf;
    }

    void sendPeerStatus(long now) {
        if (!peerStatusDirty || !peerStatusSupported || (peerStatusSentAt != 0 && now - peerStatusSentAt < 5_000_000_000L)) {
            return;
        }
        String message = "{\"type\":\"status\",\"muted\":" + selfMuted + ",\"deaf\":" + selfDeaf + "}";
        if (message.equals(lastPeerStatus)) {
            peerStatusDirty = false;
        } else if (sendText(message)) {
            lastPeerStatus = message;
            peerStatusSentAt = now;
            peerStatusDirty = false;
        }
    }

    private static boolean optionalBoolean(JsonObject object, String key) {
        if (!object.has(key)) {
            return false;
        }
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid status flag");
        }
        return value.getAsBoolean();
    }

    public synchronized void transmit(byte[] packet) {
        WebSocket current = socket;
        if (!canTransmit() || current == null) {
            return;
        }
        if (udpActive && udp.send(UdpAudio.AUDIO_UP, packet)) {
            observer.message(true, true, packet.length);
            return;
        }
        // Skip new audio during temporary TCP congestion instead of building more
        // delay or tearing down a healthy control/room connection. Heartbeat
        // health checks still close a connection that actually stops responding.
        if (current.queueSize() > 2048) {
            return;
        }
        if (!current.send(ByteString.of(packet))) {
            fail("Voice send failed");
        } else {
            observer.message(true, true, packet.length);
        }
    }

    public synchronized void listening(boolean deaf, Set<Long> blocked) {
        this.deaf = deaf;
        this.blocked = new HashSet<>(blocked);
        sendState();
    }

    private void sendState() {
        if (salt.isEmpty() || refreshingKey) {
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "state");
        msg.addProperty("token", proximitySuspended && !inRoom() ? "" : token);
        boolean exclusive = requiredRoom != null || !restoringRoom.isEmpty() || failedRoomRecovery;
        if (proximityRoomsSupported) {
            msg.addProperty("region", exclusive ? "" : region);
        }
        if (keyEpoch != 0) {
            msg.addProperty("keyEpoch", keyEpoch);
        }
        msg.addProperty("deaf", deaf || !scopeAllowed());
        JsonArray list = new JsonArray();
        for (long id : new java.util.TreeSet<>(blocked)) {
            list.add(id);
        }
        msg.add("blocked", list);
        Set<Long> near = exclusive ? Collections.emptySet() : new java.util.TreeSet<>(nearby);
        Set<String> sightings =
                !exclusive && !region.isEmpty() && !(proximitySuspended && !inRoom())
                        ? new java.util.TreeSet<>(seen)
                        : Collections.emptySet();
        String text = gson.toJson(msg);
        boolean full = resendLists;
        if (!full && text.equals(lastState) && near.equals(sentNear) && sightings.equals(sentSeen)) {
            return;
        }
        listChange(msg, "near", near, full ? null : sentNear, JsonArray::add);
        listChange(msg, "seen", sightings, full ? null : sentSeen, null);
        msg.addProperty("rev", revision + 1);
        if (sendText(gson.toJson(msg))) {
            revision++;
            lastState = text;
            sentNear = near;
            sentSeen = sightings;
            resendLists = false;
        }
    }

    /**
     * Adds one set to a state: the full list, or nameAdd/nameRemove when that is smaller, or
     * nothing when it is unchanged. Fingerprints (adder null) are concatenated hex strings.
     */
    private static <T> void listChange(
            JsonObject msg,
            String name,
            Set<T> next,
            Set<T> previous,
            java.util.function.BiConsumer<JsonArray, T> adder) {
        if (previous != null && next.equals(previous)) {
            return;
        }
        List<T> added = new ArrayList<>(), removed = new ArrayList<>();
        if (previous != null) {
            for (T item : next) {
                if (!previous.contains(item)) {
                    added.add(item);
                }
            }
            for (T item : previous) {
                if (!next.contains(item)) {
                    removed.add(item);
                }
            }
        }
        if (previous == null || added.size() + removed.size() >= next.size()) {
            msg.add(name, encode(new ArrayList<>(next), adder));
            return;
        }
        if (!added.isEmpty()) {
            msg.add(name + "Add", encode(added, adder));
        }
        if (!removed.isEmpty()) {
            msg.add(name + "Remove", encode(removed, adder));
        }
    }

    private static <T> JsonElement encode(
            List<T> items, java.util.function.BiConsumer<JsonArray, T> adder) {
        if (adder == null) {
            StringBuilder text = new StringBuilder();
            new java.util.TreeSet<>(items).forEach(text::append);
            return new com.google.gson.JsonPrimitive(text.toString());
        }
        JsonArray array = new JsonArray();
        for (T item : items) {
            adder.accept(array, item);
        }
        return array;
    }

    private void send(JsonObject msg) {
        sendText(gson.toJson(msg));
    }

    private boolean sendText(String text) {
        WebSocket current = socket;
        if (!open || closed || current == null || !current.send(text)) {
            return false;
        }
        if (observer != VoiceObserver.NONE) {
            observer.message(
                    true, false, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        return true;
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        log.debug(
                "RSVC relay closing: code={}, category={}",
                code,
                DisconnectReason.fromClose(code, reason));
        webSocket.close(code, null);
        DisconnectReason category = DisconnectReason.fromClose(code, reason);
        fail(
                category == DisconnectReason.SERVER_FULL
                        ? "Voice server full"
                        : "Relay disconnected (" + code + ")",
                category);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        // Exception messages and server-provided close text may contain sensitive data.
        log.debug(
                "RSVC transport failure: exception={}, cause={}, http={}",
                t.getClass().getSimpleName(),
                t.getCause() == null ? "none" : t.getCause().getClass().getSimpleName(),
                response == null ? 0 : response.code());
        DisconnectReason category =
                response == null
                        ? DisconnectReason.NETWORK
                        : DisconnectReason.fromHttp(
                                response.code(), response.header("X-RSVC-Error"));
        fail(
                category == DisconnectReason.SERVER_FULL
                        ? "Voice server full"
                        : response == null
                                ? "Connection failed — check relay URL/network"
                                : "Relay refused connection (HTTP " + response.code() + ")",
                category);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        log.debug(
                "RSVC relay closed: code={}, category={}",
                code,
                DisconnectReason.fromClose(code, reason));
        DisconnectReason category = DisconnectReason.fromClose(code, reason);
        fail(
                category == DisconnectReason.SERVER_FULL ? "Voice server full" : "Disconnected",
                category);
    }

    private void fail(String message) {
        fail(message, true);
    }

    private void fail(String message, boolean retry) {
        fail(message, retry ? DisconnectReason.NETWORK : DisconnectReason.REJECTED);
    }

    private synchronized void fail(String message, DisconnectReason reason) {
        if (closed) {
            return;
        }
        log.debug(
                "RSVC disconnect: category={}, detail={}, active={}, room={}, pending={},"
                        + " scopeAllowed={}, revision={}, acknowledged={}, peers={}, listeners={}",
                reason, message, activeConnection(), inRoom(), roomPending, scopeAllowed(),
                revision, acknowledgedRevision, peers.size(), listeners);
        close();
        status.accept(message);
        ended.accept(reason);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        open = false;
        active = false;
        activityAt = 0;
        udpActive = false;
        if (udp != null) {
            udp.close();
        }
        observer.connected(false);
        WebSocket current = socket;
        if (current != null) {
            current.cancel();
        }
    }
}
