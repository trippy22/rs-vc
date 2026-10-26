package com.rsvc.client;

import com.rsvc.network.DisconnectReason;

/** Network and capacity retries back off independently; policy and identity conflicts are bounded. */
public final class ReconnectPolicy {
    private int failures;
    private int aliasRetries;
    private int networkFailures;
    private int capacityFailures;

    public long nextDelayMillis(DisconnectReason reason) {
        if (reason == DisconnectReason.SERVER_FULL) {
            long delay = Math.min(30000, 4000L << capacityFailures);
            capacityFailures = Math.min(3, capacityFailures + 1);
            return delay;
        }
        if (reason == DisconnectReason.NETWORK) {
            long delay = Math.min(30000, 1000L << networkFailures);
            networkFailures = Math.min(5, networkFailures + 1);
            return delay;
        }
        if (reason == DisconnectReason.REJECTED) {
            return -1;
        }
        if (reason == DisconnectReason.ALIAS_IN_USE && aliasRetries++ >= 3) {
            return -1;
        }
        return nextDelayMillis();
    }

    public long nextDelayMillis() {
        if (failures >= 6) {
            return -1;
        }
        return Math.min(30000, 1000L << failures++);
    }

    public void reset() {
        failures = 0;
        aliasRetries = 0;
        networkFailures = 0;
        capacityFailures = 0;
    }
}
