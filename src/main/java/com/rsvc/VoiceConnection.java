package com.rsvc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class VoiceConnection extends WebSocketListener implements AutoCloseable
{
    public static final class Peer
    {
        public final long id;
        public final String name;
        public Peer(long id, String name) { this.id = id; this.name = name; }
    }
    private final Gson gson;
    private final Consumer<String> status;
    private final Consumer<List<Peer>> roster;
    private final Consumer<VoicePacket> receiver;
    private final Consumer<Boolean> ended;
    private final String name;
    private final VoiceMetrics metrics;
    private long heartbeatSentAt;
    private volatile long heartbeatAckAt;
    private volatile boolean active;
    private String lastListen = "";
    private volatile WebSocket socket;
    private volatile boolean open;
    private volatile boolean closed;
    private volatile boolean deaf;
    private volatile long activityAt;
    private volatile long openedAt;
    private volatile List<Peer> peers = Collections.emptyList();
    private volatile Set<Long> blocked = Collections.emptySet();
    private volatile Set<String> ignored = Collections.emptySet();

    public VoiceConnection(Gson gson, String name, Consumer<String> status, Consumer<List<Peer>> roster,
        Consumer<VoicePacket> receiver, Consumer<Boolean> ended)
    {
        this(gson, name, status, roster, receiver, ended, new VoiceMetrics());
    }

    public VoiceConnection(Gson gson, String name, Consumer<String> status, Consumer<List<Peer>> roster,
        Consumer<VoicePacket> receiver, Consumer<Boolean> ended, VoiceMetrics metrics)
    {
        this.gson = gson; this.name = name; this.status = status; this.roster = roster; this.receiver = receiver; this.ended = ended;
        this.metrics = metrics;
    }

    public static URI validateEndpoint(String endpoint)
    {
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        boolean local = "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host) || "localhost".equals(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
            !("wss".equals(uri.getScheme()) || (local && "ws".equals(uri.getScheme()))) || !"/v1/voice".equals(uri.getPath()))
            throw new IllegalArgumentException("Use wss://host/v1/voice (ws:// allowed only on loopback)");
        return uri;
    }

    public static String validateChannel(String channel)
    {
        String normalized = channel.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{3,32}")) throw new IllegalArgumentException("Channel: 3–32 letters, numbers, _ or -");
        return normalized;
    }

    public static void validatePassword(String password)
    {
        if (!password.matches("[!-~]{6,128}")) throw new IllegalArgumentException("Password: 6–128 characters, no spaces");
    }

    public void connect(OkHttpClient client, String endpoint, String channel, String password)
    {
        validateEndpoint(endpoint);
        channel = validateChannel(channel); validatePassword(password);
        Request.Builder request = new Request.Builder().url(endpoint)
            .header("X-Voice-Channel", channel).header("Authorization", "Bearer " + password);
        socket = client.newWebSocket(request.build(), this);
    }

    @Override public synchronized void onOpen(WebSocket webSocket, Response response)
    {
        if (closed) { webSocket.cancel(); return; }
        socket = webSocket; open = true;
        metrics.connected(true);
        openedAt = heartbeatAckAt = System.nanoTime();
        JsonObject hello = new JsonObject(); hello.addProperty("type", "hello"); hello.addProperty("v", 2); hello.addProperty("name", name);
        send(hello); sendListen(); status.accept("Connected — hold push-to-talk to speak");
    }

    @Override public void onMessage(WebSocket webSocket, String text)
    {
        if (closed) return;
        metrics.message(false, false, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if (text.length() > 16384) { fail("Relay sent oversized control data", false); return; }
        try
        {
            JsonObject msg = gson.fromJson(text, JsonObject.class);
            String type = msg.get("type").getAsString();
            if ("welcome".equals(type)) { if (msg.get("v").getAsInt() != 2) fail("Update the plugin and relay together", false); return; }
            if ("heartbeat".equals(type)) { heartbeatAckAt = System.nanoTime(); return; }
            if (!"peers".equals(type)) throw new IllegalArgumentException();
            JsonArray array = msg.getAsJsonArray("peers");
            if (array.size() > 64) throw new IllegalArgumentException();
            List<Peer> next = new ArrayList<>();
            for (JsonElement item : array)
            {
                JsonObject p = item.getAsJsonObject(); long id = p.get("id").getAsLong(); String n = p.get("name").getAsString();
                if (id < 1 || id > 0xffffffffL || !n.matches("[A-Za-z0-9 _-]{1,12}")) throw new IllegalArgumentException();
                next.add(new Peer(id, n));
            }
            peers = Collections.unmodifiableList(next); roster.accept(peers);
        }
        catch (RuntimeException e) { fail("Relay sent invalid control data", false); }
    }

    @Override public void onMessage(WebSocket webSocket, ByteString bytes)
    {
        if (closed) return;
        metrics.message(false, true, bytes.size());
        if (!canReceive()) return;
        try
        {
            VoicePacket packet = VoicePacket.downstream(bytes.toByteArray());
            for (Peer peer : peers)
                if (peer.id == packet.speaker && !blocked.contains(peer.id) && !ignored.contains(normalize(peer.name))) { receiver.accept(packet); return; }
        }
        catch (IllegalArgumentException ignored) { /* Malformed audio never reaches the decoder. */ }
    }

    private boolean canReceive() { return canTransmit() && !deaf; }
    public boolean canTransmit() { return open && !closed && active && activityAt != 0 && System.nanoTime() - activityAt < 2_500_000_000L; }
    public boolean isStable() { return open && !closed && System.nanoTime() - openedAt > 30_000_000_000L; }
    public void checkHealth()
    {
        if (open && !closed && System.nanoTime() - heartbeatAckAt > 15_000_000_000L) fail("Relay stopped responding");
    }

    public synchronized void heartbeat(boolean loggedIn)
    {
        boolean changed = active != loggedIn;
        active = loggedIn; activityAt = System.nanoTime();
        long now = System.nanoTime();
        if (!changed && heartbeatSentAt != 0 && now - heartbeatSentAt < 5_000_000_000L) return;
        if (sendText(loggedIn ? "{\"type\":\"heartbeat\",\"active\":true}" : "{\"type\":\"heartbeat\",\"active\":false}")) heartbeatSentAt = now;
    }

    public void transmit(byte[] packet)
    {
        WebSocket current = socket;
        if (!canTransmit() || current == null) return;
        // At 16 kbps, a few hundred bytes already represent audible delay.
        if (current.queueSize() > 2048) { fail("Voice connection too slow — reconnect when stable"); return; }
        if (!current.send(ByteString.of(packet))) fail("Voice send failed");
        else metrics.message(true, true, packet.length);
    }

    public void listening(boolean deaf, Set<Long> blocked, Set<String> ignored)
    {
        this.deaf = deaf; this.blocked = new HashSet<>(blocked); this.ignored = new HashSet<>(ignored); sendListen();
    }

    private synchronized void sendListen()
    {
        JsonObject msg = new JsonObject(); msg.addProperty("type", "listen"); msg.addProperty("deaf", deaf);
        JsonArray list = new JsonArray(); for (long id : new java.util.TreeSet<>(blocked)) list.add(id); msg.add("blocked", list);
        String text = gson.toJson(msg);
        if (!text.equals(lastListen) && sendText(text)) lastListen = text;
    }
    private void send(JsonObject msg) { sendText(gson.toJson(msg)); }
    private boolean sendText(String text)
    {
        WebSocket current = socket;
        if (!open || closed || current == null || !current.send(text)) return false;
        metrics.message(true, false, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length); return true;
    }
    public static String normalize(String name) { return name.replace('\u00a0', ' ').replace('_', ' ').toLowerCase(java.util.Locale.ROOT); }
    @Override public void onClosing(WebSocket webSocket, int code, String reason) { webSocket.close(code, null); fail("Relay disconnected (" + code + ")", code != 1008); }
    @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) { fail(response == null ? "Connection failed — check relay URL/network" : response.code() == 401 ? "Invalid channel or password" : "Relay refused connection (HTTP " + response.code() + ")", response == null || response.code() >= 500 || response.code() == 429); }
    @Override public void onClosed(WebSocket webSocket, int code, String reason) { fail("Disconnected"); }
    private void fail(String message) { fail(message, true); }
    private synchronized void fail(String message, boolean retry) { if (closed) return; close(); status.accept(message); ended.accept(retry); }
    @Override public synchronized void close() { if (closed) return; closed = true; open = false; active = false; activityAt = 0; metrics.connected(false); WebSocket current = socket; if (current != null) current.cancel(); }
}
