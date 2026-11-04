package com.rsvc.network;

/** Relay close categories; ordinary protocol rejection must never trigger an automatic retry. */
public enum DisconnectReason {
    NETWORK,
    SERVER_FULL,
    ALIAS_IN_USE,
    IDENTITY_IN_USE,
    REJECTED;

    public static DisconnectReason fromClose(int code) {
        if (code == 4013) {
            return SERVER_FULL;
        }
        if (code == 4009) {
            return ALIAS_IN_USE;
        }
        if (code == 4010) {
            return IDENTITY_IN_USE;
        }
        return code == 1008 ? REJECTED : NETWORK;
    }

    public static DisconnectReason fromHttp(int code, String relayError) {
        if (code == 503 && "server_full".equals(relayError)) {
            return SERVER_FULL;
        }
        return code >= 500 || code == 429 ? NETWORK : REJECTED;
    }
}
