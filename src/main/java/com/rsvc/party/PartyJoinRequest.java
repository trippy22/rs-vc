package com.rsvc.party;

/**
 * A Join party voice click made before RuneLite has synced the local party member, or before the
 * relay is ready for rooms. RuneLite reports the party a moment before its own member record, so
 * the click waits briefly instead of being dropped. Client thread only.
 */
public final class PartyJoinRequest {
    public enum Step {
        NONE,
        WAIT,
        START,
        EXPIRED
    }

    static final long WAIT_MS = 15_000;
    private long until;

    public void request(long now) {
        until = now + WAIT_MS;
    }

    public void cancel() {
        until = 0;
    }

    public boolean pending() {
        return until != 0;
    }

    /** Called each tick; START and EXPIRED end the request. */
    public Step step(boolean ready, long now) {
        if (until == 0) {
            return Step.NONE;
        }
        if (ready) {
            until = 0;
            return Step.START;
        }
        if (now >= until) {
            until = 0;
            return Step.EXPIRED;
        }
        return Step.WAIT;
    }
}
