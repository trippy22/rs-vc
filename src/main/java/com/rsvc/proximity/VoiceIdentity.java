package com.rsvc.proximity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Matching only: names and worlds never leave this helper in plaintext. Not account authentication.
 */
public final class VoiceIdentity {
    private VoiceIdentity() {}

    public static String normalize(String name) {
        return name.replace('\u00a0', ' ').replace('_', ' ').trim().toLowerCase(Locale.ROOT);
    }

    public static String token(String salt, String name, int world) {
        if (salt == null
                || !salt.matches("[0-9a-f]{64}")
                || name == null
                || world < 1
                || world > 65535) {
            throw new IllegalArgumentException("Invalid matching context");
        }
        String normalized = normalize(name);
        if (!normalized.matches("[a-z0-9 -]{1,12}")) {
            throw new IllegalArgumentException("Character name unavailable");
        }
        return digest(salt, "rs-vc-4\n" + world + "\n" + normalized);
    }

    /** Opaque region bucket, not encryption: the relay can enumerate possible regions. */
    public static String region(String salt, int world, int region, int plane) {
        if (salt == null
                || !salt.matches("[0-9a-f]{64}")
                || world < 1
                || world > 65535
                || region < 0
                || region > 65535
                || plane < 0
                || plane > 3) {
            throw new IllegalArgumentException("Invalid proximity context");
        }
        return digest(salt, "rs-vc-region-1\n" + world + "\n" + region + "\n" + plane);
    }

    private static String digest(String salt, String context) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) Integer.parseInt(salt.substring(i * 2, i * 2 + 2), 16);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(context.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) {
                result.append(Character.forDigit((b >>> 4) & 15, 16))
                        .append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Voice matching unavailable", e);
        }
    }
}
