package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * The server's Distant Horizons preview pool (Riven docs/DECISIONS.md, 2026-10-30, "Bounded pools for preview
 * sampling"): {@code previewThreads} threads, by default a quarter of the processors, 1 to 8, sample preview tiles in
 * place of DH's world generation executor. DH still picks which tile comes next; admission lets at most the
 * governor's permits plus one tile be in progress, so DH's choice stays current, and a tile over the permits after a
 * cut waits between its rows ({@link #checkpoint}). The {@link PreviewGovernor} sets the permits once a second from
 * the server's ticks. The pool changes when previews are made, never what they hold.
 */
public final class PreviewPool implements Executor {
    private static final long UPDATE_PERIOD = TimeUnit.SECONDS.toNanos(1);

    private final ThreadPoolExecutor threads;
    private final LivePermits permits;
    private final PreviewGovernor governor;
    private volatile boolean waiting, ran;
    private long nextUpdate = Long.MIN_VALUE;

    private PreviewPool(int size) {
        AtomicInteger count = new AtomicInteger();
        threads = new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "Cubature preview " + count.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        threads.allowCoreThreadTimeOut(true);
        governor = new PreviewGovernor(size);
        permits = new LivePermits(governor.permits());
    }

    /** The configured size: {@code previewThreads}, or a quarter of the processors, 1 to 8; 0 keeps DH's executor. */
    public static int size() {
        int threads = CubicChunksConfig.previewThreads;
        return threads < 0 ? Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 4)) : threads;
    }

    /** A pool of the configured size, or none when previews stay on DH's executor. */
    @Nullable public static PreviewPool open() {
        int size = size();
        if (size == 0) return null;
        CubicChunks.LOGGER.info("Cubature preview pool: {} threads, {} permits to start", size, Math.max(1, size / 2));
        return new PreviewPool(size);
    }

    /** Runs one tile once a permit is free. An interrupted wait still runs it, so its future fails on the interrupt. */
    @Override public void execute(Runnable tile) {
        threads.execute(() -> {
            boolean held = false;
            try {
                permits.acquire();
                held = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran = true;
            try {
                tile.run();
            } finally {
                if (held) permits.release();
                ran = true;
            }
        });
    }

    /** Between a tile's rows: a tile over the permits waits here until one is free. */
    public void checkpoint() throws InterruptedException {
        permits.checkpoint();
    }

    /**
     * Whether DH may start another tile while {@code inProgress} of its tiles are in progress: up to the permits plus
     * one, the one waiting for the next free permit.
     */
    public boolean admits(int inProgress) {
        if (inProgress <= permits.permits()) return true;
        waiting = true;
        return false;
    }

    /**
     * Once a second, from the end of a server tick: the governor reads the latest ticks.
     *
     * @param tickNanos the server's ring of tick durations
     * @param tick the server's tick counter; the entry for {@code tick} is the latest finished tick
     */
    public void govern(long now, long[] tickNanos, int tick) {
        if (now < nextUpdate) return;
        nextUpdate = now + UPDATE_PERIOD;
        int count = Math.min(PreviewGovernor.RAISE_TICKS, Math.min(tick, tickNanos.length));
        long[] latest = new long[count];
        for (int i = 0; i < count; i++) latest[i] = tickNanos[Math.floorMod(tick - count + 1 + i, tickNanos.length)];
        boolean waited = waiting, worked = ran || permits.active() > 0;
        waiting = ran = false;
        int before = permits.permits(), after = governor.update(now, latest, waited, worked);
        permits.set(after);
        summarize(before, after, PreviewGovernor.p95Ms(latest, PreviewGovernor.CUT_TICKS));
    }

    // A line a minute: the permits' range and mean, how often they changed, and the tick tail that drove them.
    private int updates, changes, least = Integer.MAX_VALUE, most;
    private long permitSum;
    private double worstP95;

    private void summarize(int before, int after, double p95) {
        updates++;
        if (after != before) changes++;
        permitSum += after;
        least = Math.min(least, after);
        most = Math.max(most, after);
        worstP95 = Math.max(worstP95, p95);
        if (updates < 60) return;
        CubicChunks.LOGGER.info("Cubature previews, last minute: permits {}-{} (mean {}), {} changes, worst tick p95 {} ms",
                least, most, String.format("%.1f", (double) permitSum / updates), changes, String.format("%.1f", worstP95));
        updates = changes = most = 0;
        least = Integer.MAX_VALUE;
        permitSum = 0;
        worstP95 = 0;
    }

    public int permits() {
        return permits.permits();
    }

    /** Stops the threads; running tiles see the interrupt and fail. */
    public void close() {
        threads.shutdownNow();
    }
}
