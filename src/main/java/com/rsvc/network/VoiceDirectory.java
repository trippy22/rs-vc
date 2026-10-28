package com.rsvc.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Protocol-7 introductions: peers the relay matched with this client (each can see the other) or
 * that share its invite room. There is no downloadable list of other players.
 */
final class VoiceDirectory {
    // A memory bound against a hostile relay: 255 mutual sightings plus room members.
    static final int MAX_ENTRIES = 512;
    private Map<Long, String> members = new HashMap<>();
    private Map<String, Long> identities = Collections.emptyMap();

    Map<String, Long> identities() {
        return identities;
    }

    static long number(JsonObject object, String key, long min, long max) {
        return number(object.get(key), min, max);
    }

    static long number(JsonElement value, long min, long max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException();
        }
        long n = value.getAsBigDecimal().longValueExact();
        if (n < min || n > max) {
            throw new IllegalArgumentException();
        }
        return n;
    }

    /** Applies one {type:"matches", add:[{id,token}], remove:[id]} change; all or nothing. */
    void accept(JsonObject message) {
        JsonArray add = message.getAsJsonArray("add"), remove = message.getAsJsonArray("remove");
        if (add == null || remove == null || add.size() + remove.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException();
        }
        Map<Long, String> next = new HashMap<>(members);
        for (JsonElement entry : remove) {
            if (next.remove(number(entry, 1, 0xffffffffL)) == null) {
                throw new IllegalArgumentException();
            }
        }
        for (JsonElement entry : add) {
            JsonObject item = entry.getAsJsonObject();
            long id = number(item, "id", 1, 0xffffffffL);
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
