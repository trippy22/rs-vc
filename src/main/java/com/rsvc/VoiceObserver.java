package com.rsvc;

/** Optional instrumentation. The release plugin retains no traffic counters or audio recordings. */
public interface VoiceObserver {
    VoiceObserver NONE = new VoiceObserver() {};

    default void connected(boolean connected) {}

    default void idle() {}

    default void encoded(int bytes, boolean suppressed) {}

    default void message(boolean outgoing, boolean audio, int bytes) {}
}
