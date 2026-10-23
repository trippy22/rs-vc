package com.rsvc;

/** Finite exponential backoff; a quota failure must not become an endless reconnect loop. */
public final class ReconnectPolicy
{
    private int failures;
    public long nextDelayMillis()
    {
        if (failures >= 6) return -1;
        return Math.min(30000, 1000L << failures++);
    }
    public void reset() { failures = 0; }
}
