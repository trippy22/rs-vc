package com.rsvc.proximity;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/** Random per-join label, with no input from the RuneScape account or character. */
public final class VoiceAlias {
    private static final SecureRandom RANDOM = new SecureRandom();
    // Compiled once: every roster entry is checked.
    private static final Pattern ALIAS = Pattern.compile("VC-[0-9a-f]{8}");

    private VoiceAlias() {}

    public static String create() {
        return String.format(java.util.Locale.ROOT, "VC-%08x", RANDOM.nextInt());
    }

    public static boolean valid(String alias) {
        return alias != null && ALIAS.matcher(alias).matches();
    }

    public static String replacement(String previous) {
        String next;
        do {
            next = create();
        } while (next.equals(previous));
        return next;
    }
}
