package com.rsvc.proximity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Client-thread matching. Unregistered players' full tokens are never returned or uploaded; only
 * a 20-bit fingerprint of each visible name leaves the client, shared by many possible names.
 */
public final class ProximityMatcher {
    public static final int RANGE = 20;
    private final Map<String, String> cache = new HashMap<>();
    private String salt = "";
    private int world;

    public static boolean inRange(
            int localX, int localY, int localPlane, int x, int y, int plane, boolean sameScene) {
        return sameScene
                && localPlane == plane
                && Math.max(Math.abs(localX - x), Math.abs(localY - y)) <= RANGE;
    }

    public Set<Long> match(
            String nextSalt, int nextWorld, Set<String> nearbyNames, Map<String, Long> registered) {
        return new HashSet<>(matchNames(nextSalt, nextWorld, nearbyNames, registered).values());
    }

    /** Names in this map stay local; only matched IDs are used for network proximity. */
    public Map<String, Long> matchNames(
            String nextSalt,
            int nextWorld,
            Set<String> visibleNames,
            Map<String, Long> registered) {
        use(nextSalt, nextWorld);
        Map<String, Long> ids = new HashMap<>();
        if (salt.isEmpty() || registered.isEmpty()) {
            return ids;
        }
        for (String name : visibleNames) {
            String token = token(name);
            Long id = token == null ? null : registered.get(token);
            if (id != null) {
                ids.put(name, id);
            }
        }
        return ids;
    }

    /** Fingerprints of up to {@code limit} names, in the given (nearest-first) order. */
    public Set<String> fingerprints(String nextSalt, int nextWorld, List<String> names, int limit) {
        use(nextSalt, nextWorld);
        Set<String> result = new TreeSet<>();
        if (salt.isEmpty()) {
            return result;
        }
        for (String name : names) {
            if (result.size() >= limit) {
                break;
            }
            String token = token(name);
            if (token != null) {
                result.add(token.substring(0, FINGERPRINT_DIGITS));
            }
        }
        return result;
    }

    public static final int FINGERPRINT_DIGITS = 5;

    private void use(String nextSalt, int nextWorld) {
        if (!salt.equals(nextSalt) || world != nextWorld) {
            clear();
            salt = nextSalt;
            world = nextWorld;
        }
    }

    private String token(String name) {
        try {
            String normalized = VoiceIdentity.normalize(name);
            if (cache.size() >= 2048 && !cache.containsKey(normalized)) {
                cache.clear();
            }
            return cache.computeIfAbsent(normalized, n -> VoiceIdentity.token(salt, n, world));
        } catch (IllegalArgumentException ignored) {
            return null; // An incomplete player name cannot be matched.
        }
    }

    public void clear() {
        cache.clear();
        salt = "";
        world = 0;
    }
}
