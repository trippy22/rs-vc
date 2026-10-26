package com.rsvc;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.rsvc.audio.AudioEngine;
import com.rsvc.network.DisconnectReason;
import com.rsvc.network.ReconnectPolicy;
import com.rsvc.network.VoiceConnection;
import com.rsvc.party.PartyVoice;
import com.rsvc.party.RsVcPartyVoice;
import com.rsvc.proximity.ProximityMatcher;
import com.rsvc.proximity.VoiceAlias;
import com.rsvc.proximity.VoiceIdentity;
import com.rsvc.ui.AudioDevicePicker;
import com.rsvc.ui.CaptureFocus;
import com.rsvc.ui.SpeakingOverlay;
import com.rsvc.ui.VoicePanel;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

import okhttp3.OkHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

/** RuneLite lifecycle and game-thread coordination for voice, audio devices and the sidebar. */
@PluginDescriptor(
        name = "RS Voice Chat",
        internalName = "rs-vc",
        description = "Opt-in proximity voice within 20 tiles",
        tags = {"voice", "proximity", "audio"})
public class RsVcPlugin extends Plugin implements KeyListener {
    private static final Logger log = LoggerFactory.getLogger(RsVcPlugin.class);
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private RsVcConfig config;
    @Inject private Gson gson;
    @Inject private OkHttpClient http;
    @Inject private KeyManager keyManager;
    @Inject private ClientToolbar toolbar;
    @Inject private ConfigManager configManager;
    @Inject private OverlayManager overlayManager;
    @Inject private PartyService partyService;
    @Inject private WSClient partySocket;

    private final PartyVoice partyVoice = new PartyVoice();
    private volatile boolean partyVoiceSelected;

    private VoicePanel panel;
    private VoiceObserver observer = VoiceObserver.NONE;
    private Runnable localTestCleanup;
    private BooleanSupplier localTestSpeaking;
    private SpeakingOverlay speakingOverlay;
    private volatile List<Player> speakingPlayers = Collections.emptyList();
    private final Map<Long, Player> matchedPlayers = new HashMap<>();
    private long nextSpeakingUpdate;
    private AudioDevicePicker devicePicker;
    private NavigationButton navigation;
    // These references/flags are also read by WebSocket and audio callbacks.
    private volatile VoiceConnection connection;
    private volatile AudioEngine audio;
    private volatile boolean muted;
    private volatile boolean deaf;
    private volatile boolean held;
    private volatile boolean keyDown;
    private volatile boolean eligible;
    private volatile long eligibleAt;
    private volatile boolean started;
    private volatile boolean focused;
    private volatile CaptureFocus captureFocus;
    // Game-thread-owned collections; workers receive copies rather than game objects.
    private final Set<Long> blocked = new HashSet<>();
    private final Set<String> mutedNames = new HashSet<>();
    private final Map<String, Integer> speakerVolumes = new HashMap<>();
    private List<VoiceConnection.Peer> peers = Collections.emptyList();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> pendingRoster =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final ReconnectPolicy reconnect = new ReconnectPolicy();
    private final GameVoiceSession gameSession = new GameVoiceSession();
    private long retryAt;
    // Client-thread intent: explicit Disconnect stays off until Connect or plugin re-enable.
    private boolean connectionWanted;
    private volatile boolean localTesting;
    private String sessionName = "";
    private volatile String sessionRoom = "";
    private boolean roomRecoveryPaused;
    // Last validated own identity only; retained across transitions, cleared on logout.
    private String sessionCharacter = "", identitySalt = "", identityToken = "";
    private int sessionWorld;
    private final Set<Player> visiblePlayers =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final ProximityMatcher matcher = new ProximityMatcher();
    private WorldView trackedView;
    private final HotkeyListener muteHotkey = new HotkeyListener(() -> config.muteHotkey()) {
        @Override
        public void hotkeyPressed() {
            clientThread.invokeLater(() -> setMicrophoneMuted(!muted));
        }
    };
    private final HotkeyListener deafenHotkey = new HotkeyListener(() -> config.deafenHotkey()) {
        @Override
        public void hotkeyPressed() {
            clientThread.invokeLater(() -> setDeafened(!deaf));
        }
    };

    @Provides
    RsVcConfig provideConfig(ConfigManager manager) {
        return manager.getConfig(RsVcConfig.class);
    }

    @Override
    protected void startUp() {
        partySocket.registerMessage(RsVcPartyVoice.class);
        muted = false;
        deaf = false;
        held = false;
        keyDown = false;
        eligible = false;
        focused = client.getCanvas().isFocusOwner();
        devicePicker =
                new AudioDevicePicker(
                        (mic, output) ->
                                clientThread.invokeLater(
                                        () -> {
                                            if (!started) {
                                                return;
                                            }
                                            if (mic.equals(config.microphone())
                                                    && output.equals(config.speakers())) {
                                                return;
                                            }
                                            configManager.setConfiguration(
                                                    RsVcConfig.GROUP, "microphone", mic);
                                            configManager.setConfiguration(
                                                    RsVcConfig.GROUP, "speakers", output);
                                        }));
        started = true;
        connectionWanted = true;
        SwingUtilities.invokeLater(
                () -> {
                    if (!started) {
                        return;
                    }
                    captureFocus =
                            new CaptureFocus(
                                    client.getCanvas(),
                                    () -> {
                                        keyDown = false;
                                        release();
                                    });
                    panel =
                            new VoicePanel(
                                    () -> clientThread.invokeLater(this::join),
                                    () -> clientThread.invokeLater(() -> leave("Disconnected")),
                                    value ->
                                            clientThread.invokeLater(() -> setMicrophoneMuted(value)),
                                    value ->
                                            clientThread.invokeLater(() -> setDeafened(value)),
                                    (id, value) ->
                                            clientThread.invokeLater(
                                                    () -> {
                                                        for (VoiceConnection.Peer peer : peers) {
                                                            if (peer.id == id) {
                                                                if (value) {
                                                                    mutedNames.add(peer.name);
                                                                } else {
                                                                    mutedNames.remove(peer.name);
                                                                }
                                                            }
                                                        }
                                                        if (audio != null) {
                                                            audio.block(id);
                                                        }
                                                        updateListening();
                                                    }),
                                    (id, level) ->
                                            clientThread.invokeLater(
                                                    () -> {
                                                        for (VoiceConnection.Peer peer : peers) {
                                                            if (peer.id == id) {
                                                                speakerVolumes.put(
                                                                        peer.name, level);
                                                            }
                                                        }
                                                        updateListening();
                                                    }),
                                    () ->
                                            clientThread.invokeLater(
                                                    () -> {
                                                        mutedNames.clear();
                                                        blocked.clear();
                                                        speakerVolumes
                                                                .entrySet()
                                                                .removeIf(e -> e.getValue() == 0);
                                                        updateListening();
                                                        refreshPeers();
                                                    }),
                                    () ->
                                            devicePicker.show(
                                                    panel, config.microphone(), config.speakers()));
                    panel.roomActions(
                            (action, code) ->
                                    clientThread.invokeLater(
                                            () -> {
                                                if (partyVoiceSelected) {
                                                    return;
                                                }
                                                VoiceConnection current = connection;
                                                if (current == null || !current.roomsAvailable()) {
                                                    status("Connect and log in before using rooms");
                                                    return;
                                                }
                                                release();
                                                if (audio != null) {
                                                    audio.clearPlayback();
                                                }
                                                current.room(action, code);
                                                roomRecoveryPaused = current.roomRecoveryPaused();
                                                panel.roomRecovery(roomRecoveryPaused);
                                            }));
                    panel.roomRecoveryActions(
                            () -> clientThread.invokeLater(() -> recoverRoom(true)),
                            () -> clientThread.invokeLater(() -> recoverRoom(false)));
                    panel.partyAction(() -> clientThread.invokeLater(this::togglePartyVoice));
                    BufferedImage icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = icon.createGraphics();
                    g.setColor(new Color(85, 200, 160));
                    g.fillRoundRect(9, 2, 6, 13, 5, 5);
                    g.drawArc(5, 6, 14, 13, 180, 180);
                    g.drawLine(12, 19, 12, 23);
                    g.dispose();
                    navigation =
                            NavigationButton.builder()
                                    .tooltip("RS Voice Chat")
                                    .icon(icon)
                                    .priority(8)
                                    .panel(panel)
                                    .build();
                    toolbar.addNavigation(navigation);
                    clientThread.invokeLater(
                            () -> {
                                if (started && connectionWanted) {
                                    join();
                                }
                            });
                });
        // Toggle keys take precedence over PTT if the user assigns the same key.
        keyManager.registerKeyListener(muteHotkey);
        keyManager.registerKeyListener(deafenHotkey);
        keyManager.registerKeyListener(this);
        speakingOverlay = new SpeakingOverlay(client, () -> speakingPlayers);
        overlayManager.add(speakingOverlay);
    }

    @Override
    protected void shutDown() {
        started = false;
        localTesting = false;
        CaptureFocus focus = captureFocus;
        captureFocus = null;
        if (focus != null) {
            focus.close();
        }
        leave("Disconnected");
        pendingRoster.set(null);
        partySocket.unregisterMessage(RsVcPartyVoice.class);
        observer = VoiceObserver.NONE;
        overlayManager.remove(speakingOverlay);
        keyManager.unregisterKeyListener(this);
        keyManager.unregisterKeyListener(muteHotkey);
        keyManager.unregisterKeyListener(deafenHotkey);
        muteHotkey.focusLost();
        deafenHotkey.focusLost();
        devicePicker.close();
        if (panel != null) {
            panel.close();
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (navigation != null) {
                        toolbar.removeNavigation(navigation);
                    }
                    navigation = null;
                });
    }

    private void status(String text) {
        VoicePanel current = panel;
        if (current != null) {
            current.status(text);
        }
    }

    private void join() {
        if (connection != null || retryAt != 0) {
            return;
        }
        leave("Connecting…");
        if (!started) {
            return;
        }
        connectionWanted = true;
        Player player = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || player == null) {
            status("Waiting for login");
            return;
        }
        try {
            VoiceConnection.validateEndpoint(relayEndpoint());
        } catch (IllegalArgumentException e) {
            connectionWanted = false;
            status(e.getMessage());
            return;
        }
        sessionName = VoiceAlias.create();
        if (panel != null) {
            panel.alias(sessionName);
            panel.connectionActive(true);
        }
        openConnection();
    }

    private void openConnection() {
        retryAt = 0;
        // Callbacks from an old connection must never stop or feed a newer audio session.
        VoiceConnection[] owner = new VoiceConnection[1];
        VoiceConnection next =
                new VoiceConnection(
                        gson,
                        sessionName,
                        message ->
                                clientThread.invokeLater(
                                        () -> {
                                            if (connection == owner[0]) {
                                                if (panel != null) {
                                                    panel.connected(owner[0].isOpen());
                                                }
                                                status(message);
                                            }
                                        }),
                        roster ->
                                pendingRoster.set(
                                        () -> {
                                            if (connection != owner[0]) {
                                                return;
                                            }
                                            for (VoiceConnection.Peer previous : peers) {
                                                if (roster.stream()
                                                                .noneMatch(p -> p.id == previous.id)
                                                        && audio != null) {
                                                    audio.block(previous.id);
                                                }
                                            }
                                            peers = roster;
                                            refreshPeers();
                                            updateListening();
                                            if (held) {
                                                captureStatus(owner[0]);
                                            } else if (roster.isEmpty()) {
                                                status("No other voices");
                                            } else if (!owner[0].canTransmit()) {
                                                status("No listeners available");
                                            } else if (!held) {
                                                status(
                                                        config.voiceMode() == VoiceMode.PUSH_TO_TALK
                                                                ? "Connected — hold push-to-talk to"
                                                                        + " speak"
                                                                : "Connected — voice-activated"
                                                                        + " mode");
                                            }
                                        }),
                        packet -> {
                            AudioEngine current = audio;
                            if (connection == owner[0]
                                    && current != null
                                    && eligible
                                    && System.nanoTime() - eligibleAt < 2_500_000_000L) {
                                current.receive(packet);
                            }
                        },
                        reason ->
                                clientThread.invokeLater(() -> onConnectionEnded(owner[0], reason)),
                        observer);
        owner[0] = next;
        next.roomListener(
                state -> {
                    if (connection != next) {
                        return;
                    }
                    boolean resetAudio =
                            !sessionRoom.equals(state.code)
                                    || "expired".equals(state.reason)
                                    || "unavailable".equals(state.reason);
                    AudioEngine currentAudio = audio;
                    if (resetAudio && currentAudio != null) {
                        currentAudio.clearPlayback();
                    }
                    // Close the audio gate immediately for an actual room transition.
                    // Membership updates within the accepted room must not deafen us.
                    if (partyVoiceSelected
                            && (!"updated".equals(state.reason)
                                    || !sessionRoom.equals(state.code))) {
                        next.requireRoom("");
                    }
                    clientThread.invokeLater(
                            () -> {
                                if (connection != next) {
                                    return;
                                }
                                boolean changed = !sessionRoom.equals(state.code);
                                if (state.restorationFailed) {
                                    release();
                                    sessionRoom = next.recoveryRoomCode();
                                    roomRecoveryPaused = true;
                                    if (panel != null) {
                                        panel.roomState(state.code, false);
                                        panel.roomRecovery(true);
                                    }
                                    status("Voice paused — room " + state.reason + "; retry room or return to nearby");
                                    return;
                                }
                                roomRecoveryPaused = next.roomRecoveryPaused();
                                sessionRoom = roomRecoveryPaused ? next.recoveryRoomCode() : state.code;
                                if (panel != null) {
                                    panel.roomRecovery(roomRecoveryPaused);
                                }
                                if ("full".equals(state.reason)) {
                                    boolean party = partyVoiceSelected;
                                    stopPartyVoice();
                                    next.requireRoom(null);
                                    updatePartyVoice();
                                    if (panel != null) {
                                        panel.roomState(state.code, false);
                                        panel.roomFull(party);
                                    }
                                    status(
                                            party
                                                    ? "Party voice room full — try again later"
                                                    : "Room full — try again later");
                                    return;
                                }
                                // Capture intent is client-thread-owned, like onClientTick.
                                if (changed
                                        || "expired".equals(state.reason)
                                        || "unavailable".equals(state.reason)) {
                                    release();
                                }
                                if (partyVoiceSelected) {
                                    partyVoice.roomChanged(
                                            state.code, state.reason, monotonicMillis());
                                    updatePartyVoice();
                                    return;
                                }
                                if (panel != null) {
                                    panel.roomState(state.code, "created".equals(state.reason));
                                }
                                if ("expired".equals(state.reason)) {
                                    status("Room expired");
                                } else if ("unavailable".equals(state.reason)) {
                                    status("Room unavailable — current voice selection unchanged");
                                } else if ("created".equals(state.reason)) {
                                    status("Room created — Room → Invite code");
                                }
                            });
                },
                partyVoiceSelected ? "" : sessionRoom,
                !partyVoiceSelected && roomRecoveryPaused);
        next.requireRoom(partyVoiceSelected ? "" : null);
        connection = next;
        audio =
                new AudioEngine(
                        config.microphone(),
                        config.speakers(),
                        () -> Math.max(0, Math.min(100, config.volume())),
                        packet -> {
                            if (connection == next
                                    && held
                                    && !muted
                                    && !deaf
                                    && eligible
                                    && System.nanoTime() - eligibleAt < 2_500_000_000L) {
                                next.transmit(packet);
                            }
                        },
                        next::canTransmit,
                        message ->
                                clientThread.invokeLater(
                                        () -> {
                                            if (connection == next) {
                                                leave(message);
                                            }
                                        }),
                        true,
                        observer);
        audio.deafen(deaf);
        updateListening();
        try {
            next.connect(http, relayEndpoint());
        } catch (RuntimeException e) {
            leave("Could not connect: " + e.getMessage());
        }
    }

    private void onConnectionEnded(VoiceConnection source, DisconnectReason reason) {
        if (connection != source) {
            return;
        }
        if (!partyVoiceSelected) {
            roomRecoveryPaused = source.roomRecoveryPaused();
            if (!source.recoveryRoomCode().isEmpty()) {
                sessionRoom = source.recoveryRoomCode();
            }
        }
        connection = null;
        if (partyVoiceSelected) {
            partyVoice.reconnect();
            sessionRoom = "";
        }
        stopAudio();
        visiblePlayers.clear();
        trackedView = null;
        peers = Collections.emptyList();
        blocked.clear();
        if (panel != null) {
            panel.roomsEnabled(false);
            panel.connected(false);
        }
        long delay = reconnect.nextDelayMillis(reason);
        log.debug(
                "RSVC reconnect: relay={}, category={}, partySelected={}, retryDelayMs={}",
                localTesting ? "local" : "hosted", reason, partyVoiceSelected, delay);
        if (delay < 0) {
            stopPartyVoice();
            connectionWanted = false;
            sessionName = "";
            if (panel != null) {
                panel.alias("");
                panel.connectionActive(false);
            }
            status("Disconnected — relay rejection or repeated identity conflict");
            return;
        }
        if (reason == DisconnectReason.ALIAS_IN_USE) {
            sessionName = VoiceAlias.replacement(sessionName);
            if (panel != null) {
                panel.alias(sessionName);
            }
            status("Alias was taken — retrying with a new random alias");
        } else if (reason == DisconnectReason.IDENTITY_IN_USE) {
            status("Character already connected — waiting for the previous connection to clear");
        } else if (reason == DisconnectReason.SERVER_FULL) {
            status("Voice server full — retrying automatically in " + delay / 1000 + " seconds");
        } else {
            status(
                    localTesting
                            ? "Local relay interrupted — start it or turn off local testing"
                            : "Voice interrupted — reconnecting shortly; release push-to-talk");
        }
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(500);
        retryAt = System.nanoTime() + (delay + jitter) * 1_000_000;
    }

    private String relayEndpoint() {
        return localTesting ? "ws://127.0.0.1:8787/v1/voice" : RsVcConfig.HOSTED_RELAY;
    }

    /** Optional dev tooling only; call on the client thread. Never reads saved release settings. */
    public void useLocalTestRelay(boolean enabled) {
        if (localTesting == enabled) {
            return;
        }
        localTesting = enabled;
        if (started) {
            restartConnection("Updating connection settings");
        }
    }

    /** Attach/detach optional tooling on the client thread; no counters exist in the plugin. */
    public boolean observe(VoiceObserver next) {
        observer = started ? java.util.Objects.requireNonNull(next) : VoiceObserver.NONE;
        if (connection != null) {
            connection.observer(observer);
        } else {
            observer.connected(false);
        }
        if (audio != null) {
            audio.observer(observer);
        }
        return started;
    }

    /**
     * Give a local diagnostic exclusive device ownership until Connect, settings change or
     * shutdown.
     */
    public boolean beginLocalAudioTest(Runnable cleanup, BooleanSupplier speaking) {
        if (!started) {
            return false;
        }
        leave("Local audio test");
        localTestCleanup = java.util.Objects.requireNonNull(cleanup);
        localTestSpeaking = java.util.Objects.requireNonNull(speaking);
        return true;
    }

    public void endLocalAudioTest(Runnable owner) {
        if (localTestCleanup == owner) {
            endLocalAudioTest();
        }
    }

    private void endLocalAudioTest() {
        Runnable cleanup = localTestCleanup;
        localTestCleanup = null;
        localTestSpeaking = null;
        speakingPlayers = Collections.emptyList();
        if (cleanup != null) {
            cleanup.run();
        }
    }

    private void stopAudio() {
        gameSession.invalidatePosition();
        endLocalAudioTest();
        matchedPlayers.clear();
        speakingPlayers = Collections.emptyList();
        eligible = false;
        release();
        AudioEngine current = audio;
        audio = null;
        if (current != null) {
            current.close();
        }
        VoicePanel view = panel;
        if (view != null) {
            view.peers(Collections.emptyList());
        }
    }

    private void leave(String message) {
        stopPartyVoice();
        connectionWanted = false;
        VoiceConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
        }
        stopAudio();
        blocked.clear();
        status(message);
        mutedNames.clear();
        speakerVolumes.clear();
        peers = Collections.emptyList();
        sessionName = "";
        sessionRoom = "";
        roomRecoveryPaused = false;
        sessionCharacter = identitySalt = identityToken = "";
        sessionWorld = 0;
        retryAt = 0;
        reconnect.reset();
        visiblePlayers.clear();
        trackedView = null;
        matcher.clear();
        if (panel != null) {
            panel.mutedCount(0);
            panel.alias("");
            panel.connectionActive(false);
            panel.roomState("");
            panel.roomsEnabled(false);
            panel.partyState(partyService.isInParty(), false, false);
        }
    }

    private void restartConnection(String message) {
        boolean resume = connectionWanted;
        leave(message);
        connectionWanted = resume;
    }

    private void awaitLogin() {
        restartConnection(connectionWanted ? "Waiting for login" : "Disconnected");
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        gameSession.state(client.getGameState());
        if (started
                && connectionWanted
                && connection == null
                && retryAt == 0
                && client.getGameState() == GameState.LOGGED_IN) {
            join();
        }
        if (retryAt != 0) {
            if (!started || gameSession.ended()) {
                awaitLogin();
                return;
            }
            if (client.getGameState() == GameState.LOGGED_IN
                    && System.nanoTime() >= retryAt) {
                openConnection();
            }
        }
        updateProximity();
        updatePartyVoice();
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private void togglePartyVoice() {
        if (!started) {
            return;
        }
        if (partyVoiceSelected) {
            leavePartyVoice("Left party voice — nearby voice");
            return;
        }
        VoiceConnection current = connection;
        if (!partyService.isInParty()
                || partyService.getLocalMember() == null
                || current == null
                || !current.roomsAvailable()) {
            status("Connect voice and join a RuneLite party first");
            return;
        }
        partyVoice.start(
                partyService.getPartyId(),
                partyService.getLocalMember().getMemberId(),
                localTesting,
                monotonicMillis());
        partyVoiceSelected = true;
        roomRecoveryPaused = false;
        if (panel != null) {
            panel.roomRecovery(false);
        }
        current.requireRoom("");
        release();
        if (audio != null) {
            audio.clearPlayback();
        }
        peers = Collections.emptyList();
        refreshPeers();
        updatePartyVoice();
        status("Joining party voice");
    }

    private void setMicrophoneMuted(boolean value) {
        if (!started) {
            return;
        }
        muted = value;
        release();
        if (panel != null) {
            panel.microphoneMuted(value);
        }
    }

    private void recoverRoom(boolean retry) {
        VoiceConnection current = connection;
        if (!started || partyVoiceSelected || current == null) {
            return;
        }
        boolean accepted = retry ? current.retryRoomRecovery() : current.returnToNearbyVoice();
        if (!accepted) {
            return;
        }
        release();
        if (audio != null) {
            audio.clearPlayback();
        }
        roomRecoveryPaused = false;
        if (!retry) {
            sessionRoom = "";
        }
        if (panel != null) {
            panel.roomRecovery(false);
            panel.roomState(current.roomCode());
        }
        status(retry ? "Rejoining room…" : "Nearby voice");
    }

    private void setDeafened(boolean value) {
        if (!started) {
            return;
        }
        deaf = value;
        release();
        if (audio != null) {
            audio.deafen(value);
        }
        updateListening();
        if (panel != null) {
            panel.deafened(value);
        }
    }

    private void leavePartyVoice(String message) {
        boolean resume = connectionWanted;
        // Closing cancels any in-flight room creation/join before returning to nearby voice.
        leave(message);
        connectionWanted = resume;
    }

    private void stopPartyVoice() {
        if (!partyVoiceSelected) {
            return;
        }
        boolean notify = partyVoice.sameParty(partyService.getPartyId());
        partyVoiceSelected = false;
        partyVoice.stop();
        if (notify) {
            sendPartyVoice();
        }
    }

    private void sendPartyVoice() {
        if (!partyService.isInParty() || partyService.getLocalMember() == null) {
            return;
        }
        try {
            partyService.send(partyVoice.message());
        } catch (IllegalStateException disconnected) {
            // RuneLite may close its party socket concurrently. Periodic discovery retries.
        }
    }

    private void updatePartyVoice() {
        if (!started) {
            return;
        }
        VoiceConnection current = connection;
        boolean inParty = partyService.isInParty() && partyService.getLocalMember() != null;
        if (partyVoiceSelected && !partyVoice.sameParty(partyService.getPartyId())) {
            leavePartyVoice("Party changed — nearby voice");
            return;
        }
        if (partyVoiceSelected) {
            long now = monotonicMillis();
            Set<Long> members = new HashSet<>();
            partyService.getMembers().forEach(member -> members.add(member.getMemberId()));
            partyVoice.retainMembers(members, now);
            if (inParty && partyVoice.advertisementDue(now)) {
                sendPartyVoice();
            }
            if (current != null) {
                PartyVoice.Command command =
                        inParty && current.roomsAvailable()
                                ? partyVoice.nextCommand(current.roomCode(), now)
                                : null;
                String required = inParty ? partyVoice.requiredRoom() : "";
                current.requireRoom(required);
                if (required.isEmpty()) {
                    release();
                    if (audio != null) {
                        audio.clearPlayback();
                    }
                }
                if (command != null) {
                    current.room(command.action, command.code);
                }
            }
        }
        if (panel != null) {
            panel.roomsEnabled(current != null && current.roomsAvailable());
            panel.partyState(
                    inParty,
                    partyVoiceSelected,
                    partyVoiceSelected
                            && inParty
                            && current != null
                            && !partyVoice.requiredRoom().isEmpty()
                            && partyVoice.requiredRoom().equals(current.roomCode()));
        }
    }

    @Subscribe
    public void onPartyChanged(PartyChanged event) {
        if (partyVoiceSelected) {
            VoiceConnection current = connection;
            if (current != null) {
                current.requireRoom("");
            }
            clientThread.invokeLater(
                    () -> {
                        if (started && partyVoiceSelected) {
                            leavePartyVoice("Party changed — nearby voice");
                        }
                    });
        }
    }

    @Subscribe
    public void onRsVcPartyVoice(RsVcPartyVoice event) {
        clientThread.invokeLater(
                () -> {
                    if (!started
                            || !partyVoiceSelected
                            || !partyVoice.sameParty(partyService.getPartyId())
                            || partyService.getMemberById(event.getMemberId()) == null) {
                        return;
                    }
                    partyVoice.receive(event, monotonicMillis());
                    updatePartyVoice();
                });
    }

    @Subscribe
    public void onUserSync(UserSync event) {
        clientThread.invokeLater(
                () -> {
                    if (started && partyVoiceSelected) {
                        partyVoice.requestAdvertisement();
                    }
                });
    }

    @Subscribe
    public void onPlayerSpawned(PlayerSpawned event) {
        if (connection != null) {
            visiblePlayers.add(event.getPlayer());
        }
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned event) {
        visiblePlayers.remove(event.getPlayer());
        matchedPlayers.values().removeIf(player -> player == event.getPlayer());
    }

    private void updateProximity() {
        VoiceConnection current = connection;
        Player self = client.getLocalPlayer();
        if (current == null || client.getGameState() != GameState.LOGGED_IN || self == null) {
            return;
        }
        String salt = current.matchingSalt();
        if (salt.isEmpty() || self.getName() == null || self.getWorldView() == null) {
            return;
        }
        WorldView view = self.getWorldView();
        if (view != trackedView) {
            // Seed once when connecting or changing scenes; spawn/despawn events maintain this set.
            visiblePlayers.clear();
            for (Player player : view.players()) {
                visiblePlayers.add(player);
            }
            trackedView = view;
        }
        WorldPoint origin = self.getWorldLocation();
        Map<String, Player> namedPlayers = new HashMap<>();
        Set<String> nearbyNames = new HashSet<>();
        for (Player player : visiblePlayers) {
            if (player == self || player.getName() == null || player.getWorldView() != view) {
                continue;
            }
            WorldPoint point = player.getWorldLocation();
            if (point.getPlane() != origin.getPlane()) {
                continue;
            }
            if (ProximityMatcher.inRange(
                    origin.getX(),
                    origin.getY(),
                    origin.getPlane(),
                    point.getX(),
                    point.getY(),
                    point.getPlane(),
                    true)) {
                namedPlayers.put(player.getName(), player);
                nearbyNames.add(player.getName());
            } else if (current.inRoom()) {
                // A room member may be visible outside the ordinary proximity radius.
                namedPlayers.put(player.getName(), player);
            }
        }
        try {
            Map<String, Long> matches =
                    matcher.matchNames(
                            salt, client.getWorld(), namedPlayers.keySet(), current.directory());
            Set<Long> nearby = new HashSet<>();
            matchedPlayers.clear();
            matches.forEach(
                    (name, id) -> {
                        matchedPlayers.put(id, namedPlayers.get(name));
                        if (nearbyNames.contains(name)) {
                            nearby.add(id);
                        }
                    });
            String ownToken = VoiceIdentity.token(salt, self.getName(), client.getWorld());
            String ownRegion = VoiceIdentity.region(
                    salt, client.getWorld(), origin.getRegionID(), origin.getPlane());
            sessionCharacter = self.getName();
            sessionWorld = client.getWorld();
            identitySalt = salt;
            identityToken = ownToken;
            eligible = true;
            eligibleAt = System.nanoTime();
            gameSession.positionUpdated(eligibleAt);
            current.proximity(
                    salt,
                    ownToken,
                    nearby,
                    ownRegion);
            if (!current.inRoom()) {
                for (VoiceConnection.Peer peer : peers) {
                    if (!nearby.contains(peer.id) && audio != null) {
                        audio.block(peer.id);
                    }
                }
            }
            if (panel != null) {
                panel.roomsEnabled(current.roomsAvailable());
            }
        } catch (IllegalArgumentException invalid) {
            pause("Voice paused — character unavailable");
        }
    }

    @Subscribe
    public void onClientTick(ClientTick tick) {
        if (started) {
            handleGameState(client.getGameState());
        }
        Runnable rosterUpdate = pendingRoster.getAndSet(null);
        if (started && rosterUpdate != null) {
            rosterUpdate.run();
        }
        updateSpeakingIndicators();
        VoiceConnection current = connection;
        if (current == null) {
            return;
        }
        if (!started) {
            leave("Disconnected");
            return;
        }
        current.selfStatus(muted, deaf);
        current.heartbeat();
        current.checkHealth();
        if (current.isStable()) {
            reconnect.reset();
        }
        if (config.voiceMode() == VoiceMode.VOICE_ACTIVATED) {
            if (canCapture(current)) {
                if (!held) {
                    held = true;
                    audio.startCapture();
                    captureStatus(current);
                }
            } else {
                release();
            }
        } else if (held && !canCapture(current)) {
            release();
        }
    }

    private boolean canCapture(VoiceConnection current) {
        CaptureFocus focus = captureFocus;
        return started
                && (config.voiceMode() == VoiceMode.VOICE_ACTIVATED
                        ? focus != null && focus.isActive()
                        : focused)
                && !muted
                && !deaf
                && eligible
                && audio != null
                && current != null
                && current.isOpen()
                && !current.awaitingRoomRecovery()
                && (current.canTransmit() || config.speakingIndicators());
    }

    private void captureStatus(VoiceConnection current) {
        status(current.canTransmit() ? "Microphone on" : "Local mic only");
    }

    private void pause(String reason) {
        gameSession.invalidatePosition();
        eligible = false;
        eligibleAt = 0;
        release();
        if (audio != null) {
            audio.clearPlayback();
        }
        VoiceConnection current = connection;
        if (current != null) {
            current.proximity(current.matchingSalt(), "", Collections.emptySet());
        }
        visiblePlayers.clear();
        matchedPlayers.clear();
        speakingPlayers = Collections.emptyList();
        trackedView = null;
        matcher.clear();
        status(reason);
    }

    private void updateListening() {
        Set<Long> nextBlocked = new HashSet<>();
        Set<String> quiet = new HashSet<>(mutedNames);
        Map<Long, Integer> levels = new HashMap<>();
        for (Map.Entry<String, Integer> entry : speakerVolumes.entrySet()) {
            if (entry.getValue() == 0) {
                quiet.add(entry.getKey());
            }
        }
        for (VoiceConnection.Peer peer : peers) {
            if (quiet.contains(peer.name)) {
                nextBlocked.add(peer.id);
            }
            levels.put(peer.id, speakerVolumes.getOrDefault(peer.name, 100));
        }
        blocked.clear();
        blocked.addAll(nextBlocked);
        VoiceConnection current = connection;
        if (current != null) {
            current.mutedAliases(quiet);
            current.listening(deaf, blocked);
        }
        if (audio != null) {
            audio.volumes(levels);
            for (long id : blocked) {
                audio.block(id);
            }
        }
        if (panel != null) {
            panel.mutedCount(quiet.size());
        }
    }

    private void refreshPeers() {
        if (panel != null) {
            panel.peers(peers, new HashMap<>(speakerVolumes), new HashSet<>(mutedNames));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        handleGameState(event.getGameState());
    }

    private void handleGameState(GameState state) {
        gameSession.state(state);
        if (gameSession.ended()) {
            if (connection != null || retryAt != 0) {
                awaitLogin();
            }
            return;
        }
        VoiceConnection current = connection;
        if (current == null
                || (gameSession.hasFreshPosition(System.nanoTime())
                        && client.getLocalPlayer() != null)) {
            return;
        }
        // No player/world reads during transitions. The accepted room remains authoritative.
        String salt = current.matchingSalt();
        if (!salt.isEmpty() && !sessionCharacter.isEmpty() && !salt.equals(identitySalt)) {
            identityToken = VoiceIdentity.token(salt, sessionCharacter, sessionWorld);
            identitySalt = salt;
        }
        boolean roomActive = current.suspendProximity(identitySalt, identityToken);
        if (eligible && !roomActive) {
            release();
            if (audio != null) {
                audio.clearPlayback();
            }
            status("Nearby voice paused — waiting for position updates");
        }
        eligible = roomActive;
        eligibleAt = roomActive ? System.nanoTime() : 0;
        visiblePlayers.clear();
        matchedPlayers.clear();
        speakingPlayers = Collections.emptyList();
        trackedView = null;
        matcher.clear();
    }

    @Subscribe
    public void onFocusChanged(FocusChanged event) {
        focused = event.isFocused();
        if (!focused) {
            keyDown = false;
            if (config.voiceMode() == VoiceMode.PUSH_TO_TALK) {
                release();
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!RsVcConfig.GROUP.equals(event.getGroup())) {
            return;
        }
        if ("muteHotkey".equals(event.getKey()) || "deafenHotkey".equals(event.getKey())) {
            SwingUtilities.invokeLater(() -> {
                muteHotkey.focusLost();
                deafenHotkey.focusLost();
            });
            return;
        }
        // Retired keys cannot change the fixed destination or grant/revoke a live session.
        if ("consent".equals(event.getKey())
                || "endpoint".equals(event.getKey())
                || "baselineAudio".equals(event.getKey())
                || "noiseReduction".equals(event.getKey())) {
            return;
        }
        // Capture policy is local: preserve the socket, room and participant preferences.
        if ("voiceMode".equals(event.getKey()) || "pushToTalk".equals(event.getKey())) {
            clientThread.invokeLater(
                    () -> {
                        endLocalAudioTest();
                        release();
                        keyDown = false;
                        // Voice activation resumes on the next tick if capture is eligible.
                    });
            return;
        }
        if ("microphone".equals(event.getKey()) || "speakers".equals(event.getKey())) {
            clientThread.invokeLater(
                    () -> {
                        endLocalAudioTest();
                        AudioEngine current = audio;
                        if (current != null
                                && current.changeDevices(config.microphone(), config.speakers())) {
                            release();
                            keyDown = false;
                            status("Audio devices updated");
                        }
                    });
            return;
        }
    }

    @Override
    public void keyPressed(KeyEvent event) {
        if (config.voiceMode() != VoiceMode.PUSH_TO_TALK
                || !config.pushToTalk().matches(event)
                || keyDown) {
            return;
        }
        keyDown = true;
        AudioEngine current = audio;
        VoiceConnection network = connection;
        if (!held && canCapture(network)) {
            held = true;
            current.startCapture();
            captureStatus(network);
        }
    }

    @Override
    public void keyReleased(KeyEvent event) {
        if (event.getKeyCode() == config.pushToTalk().getKeyCode()
                || !config.pushToTalk().matches(event)) {
            keyDown = false;
            if (config.voiceMode() == VoiceMode.PUSH_TO_TALK) {
                release();
            }
        }
    }

    @Override
    public void keyTyped(KeyEvent event) {}

    private void release() {
        boolean wasHeld = held;
        held = false;
        AudioEngine current = audio;
        if (current != null && wasHeld) {
            current.stopCapture();
        }
        if (wasHeld) {
            status("Connected — microphone idle");
        }
    }

    private void updateSpeakingIndicators() {
        long now = System.nanoTime();
        if (now < nextSpeakingUpdate) {
            return;
        }
        nextSpeakingUpdate = now + 100_000_000L;
        Player self = client.getLocalPlayer();
        if (!started
                || !config.speakingIndicators()
                || client.getGameState() != GameState.LOGGED_IN
                || self == null) {
            speakingPlayers = Collections.emptyList();
            return;
        }
        List<Player> active = new ArrayList<>();
        BooleanSupplier testSpeaking = localTestSpeaking;
        AudioEngine current = audio;
        boolean live = eligible && now - eligibleAt < 2_500_000_000L;
        if ((testSpeaking != null && testSpeaking.getAsBoolean())
                || (live
                        && current != null
                        && held
                        && !muted
                        && !deaf
                        && current.microphoneSpeaking())) {
            active.add(self);
        }
        if (live && current != null && !deaf) {
            for (long id : current.speaking()) {
                Player player = matchedPlayers.get(id);
                if (player != null && visiblePlayers.contains(player) && !blocked.contains(id)) {
                    active.add(player);
                }
            }
        }
        speakingPlayers = active;
    }
}
