package com.rsvc.network;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Invite codes as the relay sends them. Long codes are 24 hex digits: every plugin release
 * accepts them, and party voice uses only these. Short codes are 8 characters of Crockford
 * base32 (no i, l, o or u), issued when a create asks for one, for people to read out and type.
 */
public final class RoomCode {
    private static final Pattern LONG = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern SHORT = Pattern.compile("[0-9a-hjkmnp-tv-z]{8}");
    private static final int SHORT_LENGTH = 8;

    private RoomCode() {}

    public static boolean valid(String code) {
        return code != null && (isLong(code) || SHORT.matcher(code).matches());
    }

    public static boolean isLong(String code) {
        return code != null && LONG.matcher(code).matches();
    }

    /**
     * The code a person typed or pasted, as the relay expects it, or null when it cannot be one.
     * Case, spaces and dashes are ignored, so ABCD-EFGH, abcdefgh and "abcd efgh" are the same
     * code. In a short code the letters O, I and L are read as the digits they resemble.
     */
    public static String normalize(String typed) {
        if (typed == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(typed.length());
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c != '-' && !Character.isWhitespace(c) && c != ' ') {
                text.append(c);
            }
        }
        String code = text.toString().toLowerCase(Locale.ROOT);
        if (code.length() == SHORT_LENGTH) {
            code = code.replace('o', '0').replace('i', '1').replace('l', '1');
        }
        return valid(code) ? code : null;
    }

    /** How a code is shown for sharing: short codes as ABCD-EFGH, long codes unchanged. */
    public static String display(String code) {
        if (code == null || code.length() != SHORT_LENGTH) {
            return code;
        }
        String upper = code.toUpperCase(Locale.ROOT);
        return upper.substring(0, 4) + "-" + upper.substring(4);
    }
}
