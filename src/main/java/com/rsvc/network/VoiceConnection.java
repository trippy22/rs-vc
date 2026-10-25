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

import java.net.URI;
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
    public static final class Peer {
        public final long id;
        public final String name;

        public Peer(long id, String name) {
            this.id = id;
            this.name = name;
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
    private String lastState = "";
    private String token = "";
    private volatile String salt = "";
    private volatile Map<String, Long> directory = Collections.emptyMap();
    private final VoiceDirectory pagedDirectory = new VoiceDirectory();
    private int protocol;
    private long keyEpoch;
    private boolean refreshingKey;
    private boolean roomsSupported, roomPending;
    private volatile String roomCode = "";
    private String resumeRoom = "";
    private Consumer<RoomState> roomListener = state -> {};

    private Set<Long> nearby = Collections.emptySet();
    private long revision;
    private long acknowledgedRevision = -1;
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

    public static final class RoomState {
        public final String code, reason;
        public final long expiresAt;

        RoomState(String code, String reason, long expiresAt) {
            this.code = code;
            this.reason = reason;
            this.expiresAt = expiresAt;
        }
    }

    public synchronized void roomListener(Consumer<RoomState> listener, String resume) {
        roomListener = listener;
        resumeRoom = resume;
    }

    public boolean inRoom() {
        return !roomCode.isEmpty();
    }

    public synchronized boolean roomsAvailable() {
        return roomsSupported && activeConnection() && !roomPending;
    }

    public synchronized void room(String action, String code) {
        if (!roomsAvailable()) {
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
        listeners = 0;
        peers = Collections.emptyList();
        roster.accept(peers);
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

    public void connect(OkHttpClient client, String endpoint) {
        validateEndpoint(endpoint);
        socket = client.newWebSocket(new Request.Builder().url(endpoint).build(), this);
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
                int version = (int) VoiceDirectory.number(msg, "v", 5, 5);
                String key = msg.get("salt").getAsString();
                if (!key.matches("[0-9a-f]{64}") || !salt.isEmpty()) {
                    throw new IllegalArgumentException();
                }
                keyEpoch =
                        msg.has("keyEpoch")
                                ? VoiceDirectory.number(msg, "keyEpoch", 1, 9007199254740991L)
                                : 0;
                protocol = version;
                salt = key;
                roomsSupported = msg.has("rooms") && msg.get("rooms").getAsBoolean();
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "hello");
                hello.addProperty("v", protocol);
                hello.addProperty("alias", name);
                if (keyEpoch != 0) {
                    hello.addProperty("keyRotation", true);
                }
                send(hello);
                return;
            }
            if (protocol == 0) {
                throw new IllegalArgumentException();
            }
            if ("matching_key".equals(type)) {
                long epoch =
                        VoiceDirectory.number(msg, "keyEpoch", keyEpoch + 1, 9007199254740991L);
                String key = msg.get("salt").getAsString();
                if (keyEpoch == 0 || !key.matches("[0-9a-f]{64}") || key.equals(salt)) {
                    throw new IllegalArgumentException();
                }
                keyEpoch = epoch;
                refreshingKey = true;
                salt = key;
                // Existing routing/audio stays valid; only fresh local matching may send state.
                // Pending directory pages and their ACKs remain ordered on this socket.
                return;
            }
            if ("room".equals(type)) {
                if (!roomsSupported) {
                    throw new IllegalArgumentException();
                }
                String code = msg.get("code").getAsString(),
                        reason = msg.get("reason").getAsString();
                long expiresAt = VoiceDirectory.number(msg, "expiresAt", 0, 9007199254740991L);
                if ((!code.isEmpty() && !code.matches("[0-9a-f]{24}"))
                        || !java.util.Arrays.asList(
                                        "created",
                                        "joined",
                                        "left",
                                        "updated",
                                        "expired",
                                        "unavailable")
                                .contains(reason)) {
                    throw new IllegalArgumentException();
                }
                boolean changed = !roomCode.equals(code);
                roomCode = code;
                if (changed) {
                    listeners = 0;
                    peers = Collections.emptyList();
                    acknowledgedRevision = -1;
                    roster.accept(peers);
                }
                roomListener.accept(new RoomState(code, reason, expiresAt));
                if ("expired".equals(reason)) {
                    JsonObject ack = new JsonObject();
                    ack.addProperty("type", "room");
                    ack.addProperty("action", "acknowledge");
                    send(ack);
                }
                if (!"updated".equals(reason)) {
                    roomPending = false;
                }
                return;
            }
            if ("heartbeat".equals(type)) {
                heartbeatAckAt = System.nanoTime();
                return;
            }
            if ("directory_page".equals(type) || "directory_delta".equals(type)) {
                if (protocol != 5) {
                    throw new IllegalArgumentException();
                }
                long seq = pagedDirectory.accept(msg);
                directory = pagedDirectory.identities();
                JsonObject ack = new JsonObject();
                ack.addProperty("type", "directory_ack");
                ack.addProperty("seq", seq);
                send(ack);
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
            if (array.size() > VoiceDirectory.MAX_MEMBERS - 1
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
                    next.add(new Peer(id, n));
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
        return activeConnection() && !deaf && !roomPending;
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

    public Map<String, Long> directory() {
        return directory;
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
        if (!matchingKey.equals(salt)) {
            return; // A key update raced local matching; retry with the new key next tick.
        }
        refreshingKey = false;
        if (!identity.isEmpty() && !identity.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid voice token");
        }
        token = identity;
        active = !identity.isEmpty();
        activityAt = System.nanoTime();
        nearby = active ? new HashSet<>(ids) : Collections.emptySet();
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
            room("join", code);
        }
    }

    public synchronized void heartbeat() {
        if (!activeConnection() && active) {
            proximity(salt, "", Collections.emptySet());
        }
        long now = System.nanoTime();
        if (salt.isEmpty() || (heartbeatSentAt != 0 && now - heartbeatSentAt < 5_000_000_000L)) {
            return;
        }
        if (sendText("{\"type\":\"heartbeat\"}")) {
            heartbeatSentAt = now;
        }
    }

    public synchronized void transmit(byte[] packet) {
        WebSocket current = socket;
        if (!canTransmit() || current == null) {
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
        msg.addProperty("token", token);
        if (keyEpoch != 0) {
            msg.addProperty("keyEpoch", keyEpoch);
        }
        msg.addProperty("deaf", deaf);
        JsonArray near = new JsonArray();
        for (long id : new java.util.TreeSet<>(nearby)) {
            near.add(id);
        }
        msg.add("near", near);
        JsonArray list = new JsonArray();
        for (long id : new java.util.TreeSet<>(blocked)) {
            list.add(id);
        }
        msg.add("blocked", list);
        String text = gson.toJson(msg);
        if (!text.equals(lastState)) {
            msg.addProperty("rev", revision + 1);
            if (sendText(gson.toJson(msg))) {
                revision++;
                lastState = text;
            }
        }
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
        webSocket.close(code, null);
        fail("Relay disconnected (" + code + ")", DisconnectReason.fromClose(code, reason));
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        fail(
                response == null
                        ? "Connection failed — check relay URL/network"
                        : "Relay refused connection (HTTP " + response.code() + ")",
                response == null || response.code() >= 500 || response.code() == 429);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        fail("Disconnected", DisconnectReason.fromClose(code, reason));
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
        observer.connected(false);
        WebSocket current = socket;
        if (current != null) {
            current.cancel();
        }
    }
}
