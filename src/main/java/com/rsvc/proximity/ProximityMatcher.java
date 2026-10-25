package com.rsvc.proximity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Client-thread matching. Unregistered players' tokens are never returned or uploaded. */
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
        if (!salt.equals(nextSalt) || world != nextWorld) {
            clear();
            salt = nextSalt;
            world = nextWorld;
        }
        Map<String, Long> ids = new HashMap<>();
        if (salt.isEmpty() || registered.isEmpty()) {
            return ids;
        }
        for (String name : visibleNames) {
            try {
                String normalized = VoiceIdentity.normalize(name);
                if (cache.size() >= 2048 && !cache.containsKey(normalized)) {
                    cache.clear();
                }
                String token =
                        cache.computeIfAbsent(normalized, n -> VoiceIdentity.token(salt, n, world));
                Long id = registered.get(token);
                if (id != null) {
                    ids.put(name, id);
                }
            } catch (IllegalArgumentException ignored) {
                /* An incomplete player name cannot be matched. */
            }
        }
        return ids;
    }

    public void clear() {
        cache.clear();
        salt = "";
        world = 0;
    }
}
