package com.rsvc.party;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Client-thread room discovery. No server changes, party secrets at the relay, or audio over Party.
 */
public final class PartyVoice {
    private static final long DISCOVERY_MS = 2_000;
    private static final long ADVERTISEMENT_MS = 5_000;
    private static final long STALE_MS = 20_000;
    private final Map<Long, Offer> offers = new HashMap<>();
    private final Map<String, Long> rejected = new HashMap<>();
    private long partyId;
    private long memberId;
    private boolean localRelay;
    private boolean active;
    private String confirmed = "";
    private Command pending;
    private long discoverAt;
    private long advertiseAt;

    public static final class Command {
        public final String action;
        public final String code;

        private Command(String action, String code) {
            this.action = action;
            this.code = code;
        }
    }

    private static final class Offer {
        private final String room;
        private final long at;

        private Offer(String room, long at) {
            this.room = room;
            this.at = at;
        }
    }

    public void start(long party, long member, boolean local, long now) {
        stop();
        active = true;
        partyId = party;
        memberId = member;
        localRelay = local;
        discoverAt = now + DISCOVERY_MS;
    }

    public void stop() {
        active = false;
        confirmed = "";
        pending = null;
        offers.clear();
        rejected.clear();
        advertiseAt = Long.MIN_VALUE;
    }

    public boolean active() {
        return active;
    }

    public boolean sameParty(long id) {
        return active && partyId == id;
    }

    public RsVcPartyVoice message() {
        return new RsVcPartyVoice(partyId, localRelay, active, confirmed);
    }

    public boolean advertisementDue(long now) {
        if (!active || now < advertiseAt) {
            return false;
        }
        advertiseAt = now + ADVERTISEMENT_MS;
        return true;
    }

    public void requestAdvertisement() {
        advertiseAt = Long.MIN_VALUE;
    }

    /** Caller validates the sender against the current RuneLite party membership. */
    public void receive(RsVcPartyVoice message, long now) {
        if (!active || !message.matches(partyId, localRelay) || message.getMemberId() == memberId) {
            return;
        }
        if (!message.participating()) {
            offers.remove(message.getMemberId());
            return;
        }
        Offer previous = offers.put(message.getMemberId(), new Offer(message.room(), now));
        if (previous == null) {
            requestAdvertisement();
        }
    }

    public void retainMembers(Set<Long> members, long now) {
        offers.entrySet()
                .removeIf(e -> !members.contains(e.getKey()) || now - e.getValue().at > STALE_MS);
        rejected.entrySet().removeIf(e -> now >= e.getValue());
    }

    private String target() {
        // Concurrent creators converge on one room without a permanent leader. Anyone still
        // connected can advertise it after its creator leaves the party.
        String target = confirmed;
        for (Offer offer : offers.values()) {
            if (!offer.room.isEmpty()
                    && !rejected.containsKey(offer.room)
                    && (target.isEmpty() || offer.room.compareTo(target) < 0)) {
                target = offer.room;
            }
        }
        return target;
    }

    /** Null permits ordinary voice; empty blocks all audio until a party room is confirmed. */
    public String requiredRoom() {
        if (!active) {
            return null;
        }
        return pending == null && confirmed.equals(target()) ? confirmed : "";
    }

    public Command nextCommand(String currentRoom, long now) {
        if (!active || pending != null) {
            return null;
        }
        String target = target();
        if (!target.isEmpty()) {
            if (!target.equals(currentRoom)) {
                pending = new Command("join", target);
            } else {
                confirmed = target;
            }
        } else if (!currentRoom.isEmpty()) {
            // The relay's create command keeps an existing room, so leave private voice first.
            pending = new Command("leave", "");
        } else if (now >= discoverAt) {
            pending = new Command("create", "");
        }
        return pending;
    }

    public void roomChanged(String code, String reason, long now) {
        if (!active || "updated".equals(reason)) {
            return;
        }
        if ("unavailable".equals(reason) || "expired".equals(reason)) {
            String failed =
                    pending != null && "join".equals(pending.action) ? pending.code : confirmed;
            if (!failed.isEmpty()) {
                // Bounded by currently advertised rooms; never retain arbitrary remote history.
                if (rejected.size() >= 128) {
                    rejected.clear();
                }
                rejected.put(failed, now + STALE_MS);
                offers.entrySet().removeIf(e -> failed.equals(e.getValue().room));
            }
            if (failed.equals(confirmed)) {
                confirmed = "";
            }
            discoverAt = now + DISCOVERY_MS;
        } else if (pending != null) {
            if (("create".equals(pending.action) && "created".equals(reason))
                    || ("join".equals(pending.action)
                            && "joined".equals(reason)
                            && pending.code.equals(code))) {
                confirmed = code;
            } else if ("leave".equals(pending.action) && "left".equals(reason)) {
                confirmed = "";
            }
        }
        pending = null;
        requestAdvertisement();
    }

    public void reconnect() {
        pending = null;
        requestAdvertisement();
    }
}
