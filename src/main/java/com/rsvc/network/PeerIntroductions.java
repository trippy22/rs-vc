package com.rsvc.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Peers the relay introduced to this client: matched nearby (each can see the other) or sharing
 * its invite room. There is no downloadable list of other players.
 */
final class PeerIntroductions {
    // A memory bound against a hostile relay: 255 mutual sightings plus room members.
    static final int MAX_ENTRIES = 512;
    private Map<Long, String> members = new HashMap<>();
    private Map<String, Long> identities = Collections.emptyMap();

    Map<String, Long> identities() {
        return identities;
    }

    /** Applies one {type:"matches", add:[{id,token}], remove:[id]} change; all or nothing. */
    void accept(JsonObject message) {
        JsonArray add = message.getAsJsonArray("add"), remove = message.getAsJsonArray("remove");
        if (add == null || remove == null || add.size() + remove.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException();
        }
        Map<Long, String> next = new HashMap<>(members);
        for (JsonElement entry : remove) {
            if (next.remove(ControlJson.number(entry, 1, 0xffffffffL)) == null) {
                throw new IllegalArgumentException();
            }
        }
        for (JsonElement entry : add) {
            JsonObject item = entry.getAsJsonObject();
            long id = ControlJson.number(item, "id", 1, 0xffffffffL);
            JsonElement value = item.get("token");
            if (value == null
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()
                    || !value.getAsString().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            next.put(id, value.getAsString());
        }
        if (next.size() > MAX_ENTRIES || new HashSet<>(next.values()).size() != next.size()) {
            throw new IllegalArgumentException();
        }
        Map<String, Long> index = new HashMap<>();
        next.forEach((id, token) -> index.put(token, id));
        members = next;
        identities = Collections.unmodifiableMap(index);
    }
}
