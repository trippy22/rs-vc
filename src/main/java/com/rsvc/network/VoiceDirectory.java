package com.rsvc.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounded protocol-5 directory. Incomplete snapshots never reach proximity matching. */
final class VoiceDirectory {
    static final int MAX_MEMBERS = 1000;
    private static final int PAGE_SIZE = 64;
    private long sequence;
    private long revision = -1;
    private long snapshotRevision;
    private int total;
    private Map<Long, String> staging;
    private Map<Long, String> members = Collections.emptyMap();
    private Map<String, Long> identities = Collections.emptyMap();

    Map<String, Long> identities() {
        return identities;
    }

    static long number(JsonObject object, String key, long min, long max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException();
        }
        long n = value.getAsBigDecimal().longValueExact();
        if (n < min || n > max) {
            throw new IllegalArgumentException();
        }
        return n;
    }

    long accept(JsonObject message) {
        long seq = number(message, "seq", 1, 9007199254740991L);
        if (seq != sequence + 1) {
            throw new IllegalArgumentException();
        }
        long nextRevision = number(message, "revision", 0, 9007199254740991L);
        String type = message.get("type").getAsString();
        if ("directory_page".equals(type)) {
            int offset = (int) number(message, "offset", 0, MAX_MEMBERS);
            int count = (int) number(message, "total", 0, MAX_MEMBERS);
            JsonArray entries = message.getAsJsonArray("members");
            if (entries.size() > PAGE_SIZE
                    || offset + entries.size() > count
                    || count > 0 && entries.size() == 0) {
                throw new IllegalArgumentException();
            }
            if (offset == 0) {
                if (staging != null || nextRevision < revision) {
                    throw new IllegalArgumentException();
                }
                staging = new HashMap<>();
                total = count;
                snapshotRevision = nextRevision;
            }
            if (staging == null
                    || offset != staging.size()
                    || count != total
                    || nextRevision != snapshotRevision) {
                throw new IllegalArgumentException();
            }
            for (JsonElement entry : entries) {
                JsonObject item = entry.getAsJsonObject();
                long id = number(item, "id", 1, 0xffffffffL);
                String identity = identity(item, false);
                if (staging.putIfAbsent(id, identity) != null) {
                    throw new IllegalArgumentException();
                }
            }
            if (new HashSet<>(staging.values()).size() != staging.size()) {
                throw new IllegalArgumentException();
            }
            if (staging.size() == total) {
                publish(staging, nextRevision);
                staging = null;
            }
        } else if ("directory_delta".equals(type)) {
            long from = number(message, "from", 0, 9007199254740991L);
            JsonArray changes = message.getAsJsonArray("changes");
            if (staging != null
                    || from != revision
                    || changes.size() < 1
                    || changes.size() > PAGE_SIZE
                    || nextRevision != from + changes.size()) {
                throw new IllegalArgumentException();
            }
            Map<Long, String> next = new HashMap<>(members);
            Set<String> unique = new HashSet<>(next.values());
            for (JsonElement entry : changes) {
                JsonObject item = entry.getAsJsonObject();
                long id = number(item, "id", 1, 0xffffffffL);
                String identity = identity(item, true), old = next.remove(id);
                if (old != null) {
                    unique.remove(old);
                }
                if (!identity.isEmpty()) {
                    if (!unique.add(identity)) {
                        throw new IllegalArgumentException();
                    }
                    next.put(id, identity);
                }
                if (next.size() > MAX_MEMBERS) {
                    throw new IllegalArgumentException();
                }
            }
            publish(next, nextRevision);
        } else {
            throw new IllegalArgumentException();
        }
        sequence = seq;
        return seq;
    }

    private static String identity(JsonObject item, boolean deletion) {
        JsonElement value = item.get("token");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException();
        }
        String identity = value.getAsString();
        if (!(deletion && identity.isEmpty()) && !identity.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException();
        }
        return identity;
    }

    private void publish(Map<Long, String> next, long nextRevision) {
        Map<String, Long> index = new HashMap<>();
        next.forEach((id, identity) -> index.put(identity, id));
        members = next;
        identities = Collections.unmodifiableMap(index);
        revision = nextRevision;
    }
}
