package com.rsvc.client;

import com.google.gson.Gson;
import com.rsvc.VoiceObserver;
import com.rsvc.network.DisconnectReason;
import com.rsvc.network.VoiceConnection;
import com.rsvc.network.VoicePacket;
import com.rsvc.proximity.VoiceAlias;
import com.rsvc.proximity.VoiceIdentity;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Voice-session ownership, recovery and game-presence policy, independent of RuneLite and Swing.
 * Commands run on the owning thread. Transport callbacks run on the calling network thread;
 * the adapter must dispatch them back before applying session changes. Volatile snapshots are
 * safe for audio callbacks. This class creates no threads, timers or blocking work.
 */
public final class VoiceClient {
    public enum GameState { LOGGED_IN, TRANSITION, LOGGED_OUT }
    public enum TickAction { NONE, JOIN, RECONNECT, WAIT_FOR_LOGIN }
    public enum GameUpdate { UNCHANGED, LOGGED_OUT, POSITION_UNAVAILABLE }

    public interface Listener {
        void status(VoiceConnection source, String message);
        void roster(VoiceConnection source, List<VoiceConnection.Peer> peers);
        void audio(VoiceConnection source, VoicePacket packet);
        void ended(VoiceConnection source, DisconnectReason reason);
        void room(VoiceConnection source, VoiceConnection.RoomState state);
    }

    private final LongSupplier nanoTime, jitterMillis;
    private final ReconnectPolicy reconnect = new ReconnectPolicy();
    private final GameVoiceSession game = new GameVoiceSession();
    private volatile VoiceConnection connection;
    private volatile boolean eligible, partySelected;
    private volatile long eligibleAt;
    private volatile String room = "";
    private boolean wanted, recoveryPaused;
    private long retryAt;
    private String alias = "";
    private String character = "", identitySalt = "", identityToken = "";
    private int world;

    public VoiceClient() {
        this(System::nanoTime, () -> ThreadLocalRandom.current().nextLong(500));
    }

    VoiceClient(LongSupplier nanoTime, LongSupplier jitterMillis) {
        this.nanoTime = nanoTime;
        this.jitterMillis = jitterMillis;
    }

    public VoiceConnection connection() { return connection; }
    public boolean owns(VoiceConnection source) { return source != null && connection == source; }
    public boolean connectionWanted() { return wanted; }
    public boolean retryPending() { return retryAt != 0; }
    public String alias() { return alias; }
    public String roomCode() { return room; }
    public boolean recoveryPaused() { return recoveryPaused; }
    public boolean partySelected() { return partySelected; }
    public boolean eligible() { return eligible; }

    public boolean audioAllowed() {
        return eligible && nanoTime.getAsLong() - eligibleAt < 2_500_000_000L;
    }

    public void requestConnection() { wanted = true; }
    public void cancelConnectionRequest() { wanted = false; }
    public void newIdentity() { alias = VoiceAlias.create(); }

    /** Prepare callbacks and room gates before the adapter starts audio and opens the socket. */
    public VoiceConnection open(Gson gson, VoiceObserver observer, Listener listener) {
        if (connection != null) {
            throw new IllegalStateException("Voice connection already exists");
        }
        retryAt = 0;
        VoiceConnection[] owner = new VoiceConnection[1];
        VoiceConnection next = new VoiceConnection(gson, alias,
                message -> listener.status(owner[0], message),
                peers -> listener.roster(owner[0], peers),
                packet -> listener.audio(owner[0], packet),
                reason -> listener.ended(owner[0], reason), observer);
        owner[0] = next;
        next.roomListener(state -> {
            if (!owns(next)) {
                return;
            }
            // Close the gate on the network thread, before queued UI/session updates.
            if (partySelected && (!"updated".equals(state.reason) || !room.equals(state.code))) {
                next.requireRoom("");
            }
            listener.room(next, state);
        }, partySelected ? "" : room, !partySelected && recoveryPaused);
        next.requireRoom(partySelected ? "" : null);
        connection = next;
        return next;
    }

    /** Null means an obsolete connection callback. Negative delay means no further retry. */
    public Long disconnected(VoiceConnection source, DisconnectReason reason) {
        if (!owns(source)) {
            return null;
        }
        if (!partySelected) {
            recoveryPaused = source.roomRecoveryPaused();
            if (!source.recoveryRoomCode().isEmpty()) {
                room = source.recoveryRoomCode();
            }
        }
        connection = null;
        if (partySelected) {
            room = "";
        }
        invalidateAudio();
        long delay = reconnect.nextDelayMillis(reason);
        if (delay < 0) {
            wanted = false;
            alias = "";
        } else {
            if (reason == DisconnectReason.ALIAS_IN_USE) {
                alias = VoiceAlias.replacement(alias);
            }
            retryAt = nanoTime.getAsLong() + (delay + jitterMillis.getAsLong()) * 1_000_000;
        }
        return delay;
    }

    public void disconnect() {
        wanted = false;
        VoiceConnection previous = connection;
        connection = null;
        if (previous != null) {
            previous.close();
        }
        invalidateAudio();
        alias = room = character = identitySalt = identityToken = "";
        recoveryPaused = partySelected = false;
        world = 0;
        retryAt = 0;
        reconnect.reset();
    }

    public TickAction tick(GameState state, boolean enabled) {
        game.state(state);
        if (enabled && wanted && connection == null && retryAt == 0
                && state == GameState.LOGGED_IN) {
            return TickAction.JOIN;
        }
        if (retryAt != 0) {
            if (!enabled || game.ended()) {
                return TickAction.WAIT_FOR_LOGIN;
            }
            if (state == GameState.LOGGED_IN && nanoTime.getAsLong() >= retryAt) {
                return TickAction.RECONNECT;
            }
        }
        return TickAction.NONE;
    }

    public boolean acceptRoom(VoiceConnection source, VoiceConnection.RoomState state) {
        if (!owns(source)) {
            return false;
        }
        recoveryPaused = state.restorationFailed || source.roomRecoveryPaused();
        room = recoveryPaused ? source.recoveryRoomCode() : state.code;
        return true;
    }

    public void requestRoom(String action, String code) {
        if (connection != null) {
            connection.room(action, code);
            recoveryPaused = connection.roomRecoveryPaused();
        }
    }

    public boolean recoverRoom(boolean retry) {
        VoiceConnection current = connection;
        if (partySelected || current == null
                || !(retry ? current.retryRoomRecovery() : current.returnToNearbyVoice())) {
            return false;
        }
        recoveryPaused = false;
        if (!retry) {
            room = "";
        }
        return true;
    }

    public void selectParty() {
        partySelected = true;
        recoveryPaused = false;
    }

    public void stopParty() { partySelected = false; }

    public void position(String salt, String name, int world, Set<Long> nearby, int region, int plane) {
        VoiceConnection current = connection;
        if (current == null) {
            return;
        }
        String token = VoiceIdentity.token(salt, name, world);
        String regionToken = VoiceIdentity.region(salt, world, region, plane);
        character = name;
        this.world = world;
        identitySalt = salt;
        identityToken = token;
        eligible = true;
        eligibleAt = nanoTime.getAsLong();
        game.positionUpdated(eligibleAt);
        current.proximity(salt, token, nearby, regionToken);
    }

    public GameUpdate gameState(GameState state, boolean playerAvailable) {
        game.state(state);
        if (game.ended()) {
            return connection != null || retryAt != 0 ? GameUpdate.LOGGED_OUT : GameUpdate.UNCHANGED;
        }
        VoiceConnection current = connection;
        if (current == null || (game.hasFreshPosition(nanoTime.getAsLong()) && playerAvailable)) {
            return GameUpdate.UNCHANGED;
        }
        String salt = current.matchingSalt();
        if (!salt.isEmpty() && !character.isEmpty() && !salt.equals(identitySalt)) {
            identityToken = VoiceIdentity.token(salt, character, world);
            identitySalt = salt;
        }
        eligible = current.suspendProximity(identitySalt, identityToken);
        eligibleAt = eligible ? nanoTime.getAsLong() : 0;
        return GameUpdate.POSITION_UNAVAILABLE;
    }

    public void invalidateAudio() {
        game.invalidatePosition();
        eligible = false;
    }

    public void pause() {
        invalidateAudio();
        eligibleAt = 0;
        if (connection != null) {
            connection.proximity(connection.matchingSalt(), "", Collections.emptySet());
        }
    }

    public void heartbeat(boolean muted, boolean deaf) {
        VoiceConnection current = connection;
        if (current != null) {
            current.selfStatus(muted, deaf);
            current.heartbeat();
            current.checkHealth();
            if (current.isStable()) {
                reconnect.reset();
            }
        }
    }
}
