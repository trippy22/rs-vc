package com.rsvc;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Ignore;
import net.runelite.api.Player;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;

@PluginDescriptor(name = "RS Voice Chat", internalName = "rs-vc", description = "Private channel voice while logged in", tags = {"voice", "channel", "audio"}, enabledByDefault = false)
public class RsVcPlugin extends Plugin implements KeyListener
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private RsVcConfig config;
    @Inject private Gson gson;
    @Inject private OkHttpClient http;
    @Inject private KeyManager keyManager;
    @Inject private ClientToolbar toolbar;

    private VoicePanel panel;
    private VoiceDiagnostics diagnostics;
    private NavigationButton navigation;
    private volatile VoiceConnection connection;
    private volatile AudioEngine audio;
    private volatile boolean muted;
    private volatile boolean deaf;
    private volatile boolean held;
    private volatile boolean keyDown;
    private volatile boolean eligible;
    private volatile long eligibleAt;
    private volatile boolean started;
    private final Set<Long> blocked = new HashSet<>(); // Client-thread owned.
    private Set<String> ignored = Collections.emptySet();
    private final Set<String> mutedNames = new HashSet<>();
    private List<VoiceConnection.Peer> peers = Collections.emptyList();
    private final Map<Long, String> knownPeers = new LinkedHashMap<>();
    private final ReconnectPolicy reconnect = new ReconnectPolicy();
    private long retryAt;
    private String sessionKey = "";
    private String sessionChannel = "";
    private String sessionName = "";

    @Provides RsVcConfig provideConfig(ConfigManager manager) { return manager.getConfig(RsVcConfig.class); }

    @Override protected void startUp()
    {
        muted = false; deaf = false; held = false; keyDown = false; eligible = false;
        ignored = Collections.emptySet();
        diagnostics = new VoiceDiagnostics();
        started = true;
        SwingUtilities.invokeLater(() -> {
            if (!started) return;
            panel = new VoicePanel((channel, key) -> clientThread.invokeLater(() -> join(channel, key)),
                () -> clientThread.invokeLater(() -> leave("Disconnected")),
                value -> clientThread.invokeLater(() -> { muted = value; release(); }),
                value -> clientThread.invokeLater(() -> { deaf = value; release(); if (audio != null) audio.deafen(value); updateListening(); }),
                (id, value) -> clientThread.invokeLater(() -> {
                    for (VoiceConnection.Peer peer : peers) if (peer.id == id) mutedNames.add(VoiceConnection.normalize(peer.name));
                    if (audio != null) audio.block(id); updateListening(); }),
                () -> clientThread.invokeLater(() -> { mutedNames.clear(); blocked.clear(); updateListening(); }),
                () -> diagnostics.show(panel));
            BufferedImage icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = icon.createGraphics(); g.setColor(new Color(85, 200, 160));
            g.fillRoundRect(9, 2, 6, 13, 5, 5); g.drawArc(5, 6, 14, 13, 180, 180); g.drawLine(12, 19, 12, 23); g.dispose();
            navigation = NavigationButton.builder().tooltip("RS Voice Chat").icon(icon).priority(8).panel(panel).build();
            toolbar.addNavigation(navigation);
        });
        keyManager.registerKeyListener(this);
    }

    @Override protected void shutDown()
    {
        started = false; leave("Disconnected"); keyManager.unregisterKeyListener(this);
        diagnostics.close();
        SwingUtilities.invokeLater(() -> { if (navigation != null) toolbar.removeNavigation(navigation); navigation = null; });
    }

    private void status(String text) { VoicePanel current = panel; if (current != null) current.status(text); }

    private void join(String channel, String token)
    {
        leave("Connecting…");
        if (!started || !config.consent()) { status("Enable voice consent in plugin settings first"); return; }
        Player player = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || player == null) { status("Log in before joining voice"); return; }
        try { VoiceConnection.validateEndpoint(config.endpoint()); channel = VoiceConnection.validateChannel(channel); VoiceConnection.validatePassword(token); }
        catch (IllegalArgumentException e) { status(e.getMessage()); return; }
        if (player.getName() == null) { status("Character name unavailable — try joining again"); return; }
        String name = player.getName().replace('\u00a0', ' ');
        if (!name.matches("[A-Za-z0-9 _-]{1,12}")) { status("Unsupported character name"); return; }
        sessionKey = token; sessionChannel = channel; sessionName = name;
        diagnostics.metrics.profile(config.baselineAudio());
        openConnection();
    }

    private void openConnection()
    {
        retryAt = 0;
        // Callbacks from an old connection must never stop or feed a newer audio session.
        VoiceConnection[] owner = new VoiceConnection[1];
        VoiceConnection next = new VoiceConnection(gson, sessionName,
            message -> clientThread.invokeLater(() -> { if (connection == owner[0]) status(message); }),
            roster -> clientThread.invokeLater(() -> {
                if (connection != owner[0]) return;
                peers = roster;
                for (VoiceConnection.Peer peer : roster) knownPeers.put(peer.id, VoiceConnection.normalize(peer.name));
                while (knownPeers.size() > 128) knownPeers.remove(knownPeers.keySet().iterator().next());
                if (panel != null) panel.peers(roster); updateListening();
            }),
            packet -> { AudioEngine current = audio; if (connection == owner[0] && current != null && eligible && System.nanoTime() - eligibleAt < 2_500_000_000L) current.receive(packet); },
            retry -> clientThread.invokeLater(() -> {
                if (connection != owner[0]) return;
                connection = null; stopAudio(); peers = Collections.emptyList(); knownPeers.clear(); blocked.clear();
                long delay = retry ? reconnect.nextDelayMillis() : -1;
                if (delay < 0) { sessionKey = ""; sessionChannel = ""; status("Disconnected — check channel, password and relay; join again"); }
                else { retryAt = System.nanoTime() + (delay + java.util.concurrent.ThreadLocalRandom.current().nextLong(500)) * 1_000_000;
                    status("Voice interrupted — reconnecting shortly; release push-to-talk"); }
            }), diagnostics.metrics);
        owner[0] = next;
        connection = next;
        audio = new AudioEngine(config.microphone(), config.speakers(), () -> Math.max(0, Math.min(100, config.volume())),
            packet -> { if (connection == next && held && !muted && !deaf && eligible && System.nanoTime() - eligibleAt < 2_500_000_000L) next.transmit(packet); },
            message -> clientThread.invokeLater(() -> { if (connection == next) leave(message); }),
            !config.baselineAudio(), diagnostics.metrics,
            pcm -> { if (connection == next && held && !muted && !deaf && eligible && System.nanoTime() - eligibleAt < 2_500_000_000L) diagnostics.recording.append(pcm); });
        audio.deafen(deaf);
        updateListening();
        try { next.connect(http, config.endpoint(), sessionChannel, sessionKey); }
        catch (RuntimeException e) { leave("Could not connect: " + e.getMessage()); }
    }

    private void stopAudio()
    {
        eligible = false; release(); AudioEngine current = audio; audio = null; if (current != null) current.close();
        diagnostics.recording.stop();
        VoicePanel view = panel; if (view != null) view.peers(Collections.emptyList());
    }
    private void leave(String message)
    {
        VoiceConnection current = connection; connection = null; if (current != null) current.close();
        stopAudio(); blocked.clear(); status(message);
        mutedNames.clear(); peers = Collections.emptyList(); knownPeers.clear(); sessionKey = ""; sessionChannel = ""; sessionName = ""; retryAt = 0; reconnect.reset();
        if (panel != null) panel.mutedCount(0);
    }

    @Subscribe public void onGameTick(GameTick tick)
    {
        if (retryAt != 0)
        {
            if (!started || !config.consent() || client.getGameState() != GameState.LOGGED_IN) { leave("Disconnected"); return; }
            if (System.nanoTime() >= retryAt) openConnection();
        }
        VoiceConnection current = connection;
        if (current == null) return;
        Set<String> names = new HashSet<>();
        for (Ignore ignore : client.getIgnoreContainer().getMembers()) names.add(VoiceConnection.normalize(ignore.getName()));
        if (!names.equals(ignored)) { ignored = names; if (audio != null) audio.clearPlayback(); updateListening(); }
    }

    @Subscribe public void onClientTick(ClientTick tick)
    {
        VoiceConnection current = connection;
        if (current == null) return;
        if (!started || !config.consent()) { leave("Disconnected"); return; }
        GameState state = client.getGameState();
        if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST) { leave("Disconnected — log in and join again"); return; }
        boolean loggedIn = state == GameState.LOGGED_IN && client.getLocalPlayer() != null;
        boolean resumed = !eligible && loggedIn;
        if (eligible && !loggedIn) pause("Voice paused while loading");
        eligible = loggedIn;
        eligibleAt = System.nanoTime();
        current.heartbeat(eligible); current.checkHealth();
        if (resumed && current.canTransmit()) status("Connected — microphone idle");
        if (current.isStable()) reconnect.reset();
    }

    private void pause(String reason)
    {
        eligible = false; eligibleAt = 0; release(); if (audio != null) audio.clearPlayback();
        VoiceConnection current = connection;
        if (current != null) current.heartbeat(false);
        status(reason);
    }

    private void updateListening()
    {
        Set<Long> nextBlocked = new HashSet<>();
        for (Map.Entry<Long, String> peer : knownPeers.entrySet())
            if (nextBlocked.size() < 64 && (mutedNames.contains(peer.getValue()) || ignored.contains(peer.getValue()))) nextBlocked.add(peer.getKey());
        blocked.clear(); blocked.addAll(nextBlocked);
        VoiceConnection current = connection; if (current != null) current.listening(deaf, blocked, ignored);
        if (panel != null) panel.mutedCount(mutedNames.size());
    }

    @Subscribe public void onGameStateChanged(GameStateChanged event)
    {
        if (connection == null && retryAt == 0) return;
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.CONNECTION_LOST)
            leave("Disconnected — log in and join again");
        else if (event.getGameState() != GameState.LOGGED_IN) pause("Voice paused while loading");
    }
    @Subscribe public void onFocusChanged(FocusChanged event) { if (!event.isFocused()) release(); }
    @Subscribe public void onConfigChanged(ConfigChanged event)
    {
        if (!RsVcConfig.GROUP.equals(event.getGroup())) return;
        // Explicitly rejoin after changes to destination, consent, devices or audio profile.
        if (!"volume".equals(event.getKey())) clientThread.invokeLater(() -> leave("Settings changed — join again"));
    }
    @Override public void keyPressed(KeyEvent event)
    {
        if (!config.pushToTalk().matches(event) || keyDown) return;
        keyDown = true;
        AudioEngine current = audio; VoiceConnection network = connection;
        if (!held && !muted && !deaf && eligible && current != null && network != null && network.canTransmit())
        { held = true; current.startCapture(); status("Transmitting — release push-to-talk to stop"); }
    }
    @Override public void keyReleased(KeyEvent event)
    {
        if (event.getKeyCode() == config.pushToTalk().getKeyCode() || !config.pushToTalk().matches(event)) { keyDown = false; release(); }
    }
    @Override public void keyTyped(KeyEvent event) {}
    private void release()
    {
        boolean wasHeld = held; held = false;
        AudioEngine current = audio; if (current != null && wasHeld) current.stopCapture();
        if (wasHeld) status("Connected — microphone idle");
    }
}
