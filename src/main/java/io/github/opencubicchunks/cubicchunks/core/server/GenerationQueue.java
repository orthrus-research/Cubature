package io.github.opencubicchunks.cubicchunks.core.server;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Bounded, keyed off-thread work for pure generation. Inside a scheduled scope the first poll of a
 * key submits it and reports WAITING; later polls report WAITING until its result is retained,
 * then READY. Outside the scope a key with no entry is READY at once, so the caller generates
 * synchronously as before. Results are pure functions of their key: eviction, failure or a
 * synchronous generation racing a worker only costs time, never changes content.
 */
final class GenerationQueue<K, V> {
    enum State { READY, WAITING }

    private static final Object RUNNING = new Object(), FAILED = new Object();

    private final Executor executor;
    private final Function<K, V> work;
    private final Consumer<Throwable> failures;
    private final int capacity;
    // Insertion order is submission order; guarded by this.
    private final Map<K, Object> entries = new LinkedHashMap<>();
    private int running;
    private boolean closed;

    GenerationQueue(Executor executor, Function<K, V> work, Consumer<Throwable> failures, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Generation queue capacity must be positive");
        this.executor = executor;
        this.work = work;
        this.failures = failures;
        this.capacity = capacity;
    }

    State poll(K key, boolean schedule) {
        synchronized (this) {
            Object entry = entries.get(key);
            if (entry == RUNNING) return State.WAITING;
            if (entry != null || !schedule || closed) return State.READY;
            if (running >= capacity) return State.WAITING;
            entries.put(key, RUNNING);
            running++;
        }
        try {
            executor.execute(() -> run(key));
        } catch (RejectedExecutionException rejected) {
            finish(key, FAILED);
        }
        return State.WAITING;
    }

    /** Removes and returns the retained result; null when there is none and the caller generates now. */
    @Nullable
    @SuppressWarnings("unchecked")
    synchronized V take(K key) {
        Object entry = entries.get(key);
        if (entry == null || entry == RUNNING) return null;
        entries.remove(key);
        return entry == FAILED ? null : (V) entry;
    }

    synchronized boolean saturated() { return running >= capacity; }

    synchronized int running() { return running; }

    synchronized int retained() { return entries.size() - running; }

    synchronized void close() {
        closed = true;
        entries.clear();
    }

    private void run(K key) {
        Object result = FAILED;
        try {
            V value = work.apply(key);
            if (value != null) result = value;
        } catch (RuntimeException e) {
            // The synchronous fallback repeats the work and reports the error in its own context.
            failures.accept(e);
        } finally {
            finish(key, result);
        }
    }

    private synchronized void finish(K key, Object result) {
        running--;
        if (closed) return;
        entries.put(key, result);
        // Retain at most capacity finished results, dropping the oldest unclaimed ones.
        int finished = entries.size() - running;
        for (Iterator<Object> it = entries.values().iterator(); finished > capacity && it.hasNext(); ) {
            if (it.next() != RUNNING) {
                it.remove();
                finished--;
            }
        }
    }
}
