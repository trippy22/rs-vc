package com.rsvc.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Strict reads of relay control fields; anything malformed is a protocol violation. */
final class ControlJson {
    private ControlJson() {}

    static long number(JsonObject object, String key, long min, long max) {
        return number(object.get(key), min, max);
    }

    /** An integral JSON number in [min, max]; fractions, strings and overflow are rejected. */
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
}
