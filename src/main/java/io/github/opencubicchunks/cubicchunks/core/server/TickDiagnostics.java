package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/** Exclusive owner-thread costs inside a complete server tick. Workers and client threads do not contribute. */
public final class TickDiagnostics {
    /**
     * RANDOM_TICK selects and ticks cubes near non-spectators; PLAYER_MAP is the player cube map's own
     * bookkeeping (watcher updates, priority lists, delivery loops) outside its nested phases;
     * NETWORK runs queued packet handlers and the network tick; TICK_EVENTS runs Forge's server and
     * world tick events, including any observer listening to them. Only measurement builds charge
     * NETWORK and TICK_EVENTS, by wrapping those vanilla call sites; the mod adds no mixin there, so
     * elsewhere that time is OTHER.
     */
    public enum Phase {
        COLUMN_INSTALL, CUBE_CONSTRUCT, CUBE_POPULATE, CUBE_FIRST_LIGHT, CUBE_TRACK_SURFACE,
        LIGHT_QUEUE, SEND, PACKET_ENCODE, DH_FEED, UNLOAD_SAVE, ARRIVALS, REGION_PREPARATION,
        RANDOM_TICK, PLAYER_MAP, NETWORK, TICK_EVENTS, OTHER
    }

    /** Immutable sample; durations sum to elapsedNanos, including uncategorized work as OTHER. */
    public record Sample(long tick, long elapsedNanos, long endedNanos, long[] phaseNanos,
                         int installedCubes, int emptyCubes) {
        public Sample { phaseNanos = phaseNanos.clone(); }
        @Override public long[] phaseNanos() { return phaseNanos.clone(); }
    }

    @FunctionalInterface
    public interface Observer {
        /** Bounded, nonblocking observation after the timed tick; never change world state here. */
        void onServerTick(Sample sample);
    }

    private static final int CAPACITY = 1200;
    private static final Recorder RECORDER = new Recorder(System::nanoTime);
    private static final Sample[] RECENT = new Sample[CAPACITY];
    private static int retained, next;
    @Nullable private static volatile Observer observer;
    @Nullable private static volatile Sample latest;
    private static long loggedAt;

    private TickDiagnostics() { }

    @Nullable public static Observer setObserver(@Nullable Observer nextObserver) {
        Observer previous = observer;
        observer = nextObserver;
        return previous;
    }

    @Nullable public static Sample latest() { return latest; }

    /** Clears a server's history outside a tick; mod-lifetime observers remain installed across server restarts. */
    public static synchronized void reset() {
        RECORDER.reset();
        Arrays.fill(RECENT, null);
        retained = next = 0;
        latest = null;
        loggedAt = 0;
    }

    /** A chronological snapshot for caller-driven governors; reading it never changes admission. */
    public static synchronized Sample[] recent() {
        Sample[] result = new Sample[retained];
        for (int i = 0; i < retained; i++) result[i] = RECENT[(next - retained + i + CAPACITY) % CAPACITY];
        return result;
    }

    public static void beginTick() { RECORDER.beginTick(); }

    public static void endTick() {
        Sample sample = RECORDER.endTick();
        synchronized (TickDiagnostics.class) {
            RECENT[next] = sample;
            next = (next + 1) % CAPACITY;
            retained = Math.min(CAPACITY, retained + 1);
            latest = sample;
        }
        Observer current = observer;
        if (current != null) current.onServerTick(sample);
        if (loggedAt == 0) loggedAt = sample.endedNanos();
        if (sample.endedNanos() - loggedAt >= 60_000_000_000L) {
            loggedAt = sample.endedNanos();
            CubicChunks.LOGGER.info("Cubature tick costs: {}", summary(recent()));
        }
    }

    public static void enter(Phase phase) { RECORDER.enter(phase); }
    public static void exit() { RECORDER.exit(); }
    public static void installedCube(boolean empty) { RECORDER.installedCube(empty); }

    static String summary(Sample[] samples) {
        if (samples.length == 0) return "no ticks";
        StringBuilder text = new StringBuilder("ticks=").append(samples.length);
        long installed = 0, empty = 0, installNanos = 0;
        for (Sample sample : samples) {
            installed += sample.installedCubes();
            empty += sample.emptyCubes();
            for (int i = Phase.CUBE_CONSTRUCT.ordinal(); i <= Phase.CUBE_TRACK_SURFACE.ordinal(); i++)
                installNanos += sample.phaseNanos[i];
        }
        for (Phase phase : Phase.values()) {
            long[] values = new long[samples.length];
            long sum = 0;
            for (int i = 0; i < values.length; i++) sum += values[i] = samples[i].phaseNanos[phase.ordinal()];
            Arrays.sort(values);
            long p95 = values[(int) Math.ceil(values.length * 0.95) - 1];
            text.append(String.format(Locale.ROOT, " %s=%.3f/%.3fms", phase.name(), sum / (values.length * 1e6), p95 / 1e6));
        }
        return text.append(String.format(Locale.ROOT, " installed=%d empty=%d installMsPerCube=%.4f (mean/p95)",
                installed, empty, installed == 0 ? 0 : installNanos / (installed * 1e6))).toString();
    }

    /** Mutable only on its owner. Stack transitions charge the old phase before changing it. */
    static final class Recorder {
        private final LongSupplier clock;
        private final long[] costs = new long[Phase.values().length];
        private int[] stack = new int[16];
        @Nullable private volatile Thread owner;
        private int depth, installed, empty;
        private long tick, started, changed;

        Recorder(LongSupplier clock) { this.clock = clock; }

        void reset() {
            if (owner != null) throw new IllegalStateException("Cannot reset inside a measured tick");
            Arrays.fill(costs, 0);
            tick = started = changed = 0;
            depth = installed = empty = 0;
        }

        void beginTick() {
            if (owner != null) throw new IllegalStateException("Server tick already being measured");
            Arrays.fill(costs, 0);
            installed = empty = 0;
            stack[0] = Phase.OTHER.ordinal();
            depth = 1;
            started = changed = clock.getAsLong();
            tick++;
            owner = Thread.currentThread();
        }

        void enter(Phase phase) {
            if (owner != Thread.currentThread()) return;
            charge();
            if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
            stack[depth++] = phase.ordinal();
        }

        void exit() {
            if (owner != Thread.currentThread()) return;
            if (depth <= 1) throw new IllegalStateException("No measured phase to leave");
            charge();
            depth--;
        }

        void installedCube(boolean noStorage) {
            if (owner != Thread.currentThread()) return;
            installed++;
            if (noStorage) empty++;
        }

        Sample endTick() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Not the measured server thread");
            long ended = charge();
            owner = null;
            depth = 0;
            return new Sample(tick, ended - started, ended, costs, installed, empty);
        }

        private long charge() {
            long now = clock.getAsLong();
            costs[stack[depth - 1]] += now - changed;
            changed = now;
            return now;
        }
    }
}
