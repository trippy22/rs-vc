package com.rsvc;

import net.runelite.api.GameState;

/** Client-thread game state and position freshness, independent of room membership. */
final class GameVoiceSession {
    private GameState state = GameState.UNKNOWN;
    private long positionAt;

    void state(GameState next) {
        if (state != GameState.LOGGED_IN || next != GameState.LOGGED_IN) {
            invalidatePosition();
        }
        state = next;
    }

    boolean ended() {
        switch (state) {
            case LOGGED_IN:
            case LOADING:
            case HOPPING:
            case CONNECTION_LOST:
            case LOGGING_IN:
                return false;
            default:
                return true;
        }
    }

    void positionUpdated(long now) {
        positionAt = now;
    }

    void invalidatePosition() {
        positionAt = 0;
    }

    boolean hasFreshPosition(long now) {
        return state == GameState.LOGGED_IN
                && positionAt != 0
                && now - positionAt < 2_500_000_000L;
    }
}
