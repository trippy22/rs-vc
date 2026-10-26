package com.rsvc.party;

import net.runelite.client.party.messages.PartyMemberMessage;

/** Small control message carried by RuneLite; audio and party passphrases never go here. */
public final class RsVcPartyVoice extends PartyMemberMessage {
    private int version = 1;
    private long partyId;
    private boolean localRelay;
    private boolean participating;
    private String room = "";

    public RsVcPartyVoice() {}

    public RsVcPartyVoice(long partyId, boolean localRelay, boolean participating, String room) {
        this.partyId = partyId;
        this.localRelay = localRelay;
        this.participating = participating;
        this.room = room;
    }

    public boolean matches(long party, boolean local) {
        return version == 1
                && partyId == party
                && localRelay == local
                && room != null
                && (room.isEmpty() || room.matches("[0-9a-f]{24}"));
    }

    public boolean participating() {
        return participating;
    }

    public String room() {
        return room;
    }
}
