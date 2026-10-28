package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** FIFO readiness notifications, serviced only by the server owner with one shared tick allowance. */
final class AsyncCompletionQueue {
    private final ConcurrentLinkedQueue<Runnable> ready = new ConcurrentLinkedQueue<>();
    private final int maximum;
    private final long nanos;
    private final LongSupplier clock;
    private volatile boolean closed;
    private boolean draining;
    private int remaining;
    private long remainingNanos;

    AsyncCompletionQueue(int maximum, long nanos, LongSupplier clock) {
        if (maximum < 1 || nanos < 1) throw new IllegalArgumentException("Completion allowance must be positive");
        this.maximum = maximum;
        this.nanos = nanos;
        this.clock = clock;
        beginTick();
    }

    boolean offer(Runnable completion) {
        if (closed) return false;
        ready.add(completion);
        if (closed) {
            ready.remove(completion);
            return false;
        }
        return true;
    }

    void beginTick() {
        remaining = maximum;
        remainingNanos = nanos;
    }

    /** An indivisible native callback may overrun; do not start another one after the allowance ends. */
    int drain() {
        if (closed || draining || remaining == 0 || remainingNanos <= 0) return 0;
        draining = true;
        long start = clock.getAsLong();
        int processed = 0;
        try {
            while (remaining > 0 && (processed == 0 || clock.getAsLong() - start < remainingNanos)) {
                Runnable completion = ready.poll();
                if (completion == null) break;
                --remaining;
                ++processed;
                completion.run();
            }
            return processed;
        } finally {
            remainingNanos -= Math.max(0L, clock.getAsLong() - start);
            draining = false;
        }
    }

    void close() {
        closed = true;
        ready.clear();
    }

    /** Thread-safe metadata disposal preserves other worlds' ready records and relative FIFO order. */
    void discard(Predicate<Runnable> remove) { ready.removeIf(remove); }
}
