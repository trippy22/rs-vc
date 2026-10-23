package com.rsvc;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;

/** Synthetic audio through the real production Java client and a running relay; no microphone/game needed. */
public final class WireIntegration
{
    private static final ScheduledExecutorService CLOCK = Executors.newScheduledThreadPool(3);
    private static final OkHttpClient HTTP = new OkHttpClient(); // Test harness only; plugin uses injection.
    // Public fixtures for a loopback relay only; configure the same two channels on the emulator.
    private static final String CHANNEL = System.getenv().getOrDefault("RSVC_TEST_CHANNEL", "friends");
    private static final String PASSWORD = System.getenv().getOrDefault("RSVC_TEST_PASSWORD", "local-test-password");
    private static final String OTHER = System.getenv().getOrDefault("RSVC_TEST_OTHER_CHANNEL", "other");
    private static final String OTHER_PASSWORD = System.getenv().getOrDefault("RSVC_TEST_OTHER_PASSWORD", "other-test-password");
    private static final class Endpoint implements AutoCloseable
    {
        final VoiceConnection connection;
        final AtomicInteger count = new AtomicInteger();
        final AtomicLong energy = new AtomicLong();
        final AtomicInteger acknowledgements = new AtomicInteger();
        final VoiceMetrics metrics = new VoiceMetrics();
        final PlaybackBuffer playback = new PlaybackBuffer();
        volatile List<VoiceConnection.Peer> peers = Collections.emptyList();
        volatile String status = "Connecting";
        volatile Boolean ended;
        volatile boolean active = true;
        final ScheduledFuture<?> heartbeat;
        final ScheduledFuture<?> playbackTask;
        long sequence;

        Endpoint(String url, String name) throws Exception
        {
            this(url, name, CHANNEL, PASSWORD);
        }

        Endpoint(String url, String name, String channel, String password) throws Exception
        {
            connection = new VoiceConnection(new Gson(), name, s -> status = s, list -> { peers = list; acknowledgements.incrementAndGet(); }, packet -> {
                try {
                    playback.offer(packet, System.nanoTime() / 1_000_000);
                    count.incrementAndGet();
                } catch (Exception e) { throw new AssertionError(e); }
            }, retry -> ended = retry, metrics);
            connection.connect(HTTP, url, channel, password);
            heartbeat = CLOCK.scheduleAtFixedRate(() -> connection.heartbeat(active), 50, 100, TimeUnit.MILLISECONDS);
            playbackTask = CLOCK.scheduleAtFixedRate(() -> {
                for (short sample : playback.mix(System.nanoTime() / 1_000_000, 100)) energy.addAndGet((long) sample * sample);
            }, 0, 20, TimeUnit.MILLISECONDS);
            try {
                await(() -> (acknowledgements.get() > 0 && connection.canTransmit()) || ended != null);
                if (ended != null) throw new IllegalStateException("Test endpoint " + name + ": " + status);
            } catch (Exception failure) { close(); throw failure; }
        }
        void active(boolean value) { active = value; connection.heartbeat(value); }
        @Override public void close() { heartbeat.cancel(false); playbackTask.cancel(false); connection.close(); }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        ScheduledFuture<?> poll = CLOCK.scheduleAtFixedRate(() -> { if (condition.getAsBoolean()) ready.complete(null); }, 0, 20, TimeUnit.MILLISECONDS);
        try { ready.get(8, TimeUnit.SECONDS); } finally { poll.cancel(false); }
    }
    private static void speak(Endpoint sender, int frames) throws Exception {
        stream(sender, frames, 0, 0);
    }
    private static int stream(Endpoint sender, int before, int silence, int after) throws Exception {
        org.concentus.OpusEncoder encoder = OpusCodec.encoder();
        int frames = before + silence + after;
        CompletableFuture<Void> done = new CompletableFuture<>(); AtomicInteger index = new AtomicInteger(), suppressed = new AtomicInteger();
        ScheduledFuture<?> task = CLOCK.scheduleAtFixedRate(() -> {
            int frame = index.getAndIncrement(); if (frame >= frames) { done.complete(null); return; }
            try {
                short[] pcm = new short[960];
                if (frame < before || frame >= before + silence) {
                    java.util.Random noise = new java.util.Random(frame);
                    // Vary the spectrum: a steady test tone is legitimately classified
                    // as background by DTX and is not a useful active-speech fixture.
                    double fundamental = 120 + (frame * 17 % 230);
                    for (int i = 0; i < pcm.length; i++) {
                        double t = (frame * 960 + i) / 48000.0;
                        pcm[i] = (short) (7000 * Math.sin(2 * Math.PI * fundamental * t)
                            + 3500 * Math.sin(2 * Math.PI * fundamental * 5 * t) + 2000 * Math.sin(2 * Math.PI * fundamental * 11 * t) + noise.nextInt(1500) - 750);
                    }
                }
                byte[] opus = OpusCodec.encode(encoder, pcm); long sequence = sender.sequence++;
                boolean dropped = OpusCodec.suppress(opus); sender.metrics.encoded(opus.length, dropped);
                if (dropped) suppressed.incrementAndGet();
                else sender.connection.transmit(VoicePacket.upstream(sequence, opus));
            } catch (Exception e) { done.completeExceptionally(e); }
        }, 0, 20, TimeUnit.MILLISECONDS);
        try { done.get(15, TimeUnit.SECONDS); } finally { task.cancel(false); sender.metrics.idle(); }
        return suppressed.get();
    }

    public static void main(String[] args) throws Exception {
        try (Endpoint alice = new Endpoint(args[0], "Alice"); Endpoint bob = new Endpoint(args[0], "Bob");
            Endpoint third = new Endpoint(args[0], "Charlie"); Endpoint other = new Endpoint(args[0], "Other", OTHER, OTHER_PASSWORD)) {
            CompletableFuture<Boolean> rejected = new CompletableFuture<>();
            try (VoiceConnection wrong = new VoiceConnection(new Gson(), "Wrong", s -> {}, list -> {}, packet -> {}, rejected::complete)) {
                wrong.connect(HTTP, args[0], CHANNEL, PASSWORD + "-incorrect");
                if (rejected.get(8, TimeUnit.SECONDS)) throw new AssertionError("Bad password should not auto-retry");
            }
            await(() -> bob.peers.size() == 2 && alice.peers.size() == 2);
            int initialSuppressed = stream(alice, 100, 0, 0);
            await(() -> bob.count.get() >= 95 - initialSuppressed);
            if (bob.energy.get() < 100000000L || third.count.get() < 95 - initialSuppressed) throw new AssertionError("Channel audibility failed");
            if (other.count.get() != 0 || !other.peers.isEmpty()) throw new AssertionError("Cross-channel audio/roster leak");
            int beforeGap = bob.count.get(); long energyBeforeGap = bob.energy.get();
            int suppressed = stream(alice, 100, 300, 100);
            await(() -> bob.count.get() >= beforeGap + 495 - suppressed);
            if (suppressed < 240 || suppressed > 320 || bob.count.get() - beforeGap > 260 || bob.energy.get() - energyBeforeGap < 1_000_000_000L)
                throw new AssertionError("Silence suppression/resumption failed: suppressed=" + suppressed
                    + ", received=" + (bob.count.get() - beforeGap) + ", energy=" + (bob.energy.get() - energyBeforeGap));
            alice.metrics.reset(); int idlePackets = bob.count.get();
            CompletableFuture<Void> idle = new CompletableFuture<>(); CLOCK.schedule(() -> idle.complete(null), 11, TimeUnit.SECONDS); idle.get(13, TimeUnit.SECONDS);
            if (bob.count.get() != idlePackets || !alice.metrics.csv().contains("tx_audio_messages,0,messages"))
                throw new AssertionError("Idle connection sent audio");
            System.out.println("PASS: suppressed " + suppressed + "/500 frames in the speech/silence/resume trial (300 silent frames); idle measurement:\n" + alice.metrics.csv());
            if (!alice.metrics.csv().contains("tx_control_messages,2,messages") && !alice.metrics.csv().contains("tx_control_messages,3,messages"))
                throw new AssertionError("Expected only 5-second heartbeats during idle");
            bob.active(false); int count = bob.count.get(); speak(alice, 20);
            if (bob.count.get() != count) throw new AssertionError("Inactive receiver leak");
            bob.active(true);
            bob.connection.listening(true, Collections.emptySet(), Collections.emptySet()); count = bob.count.get(); speak(alice, 20);
            if (bob.count.get() != count) throw new AssertionError("Deafen leak");
            long aliceId = bob.peers.stream().filter(p -> p.name.equals("Alice")).findFirst().get().id;
            bob.connection.listening(false, Collections.singleton(aliceId), Collections.emptySet()); speak(alice, 20);
            if (bob.count.get() != count) throw new AssertionError("Muted speaker leak");
            System.out.println("PASS: channel-wide Opus speech/silence/resume without location messages; password rejection, private channels, idle heartbeats, inactive clients, deafen and mute isolation passed.");
        } finally { CLOCK.shutdownNow(); HTTP.dispatcher().executorService().shutdown(); HTTP.connectionPool().evictAll(); }
    }
}
