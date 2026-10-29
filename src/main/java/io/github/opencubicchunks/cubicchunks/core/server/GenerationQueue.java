package io.github.opencubicchunks.cubicchunks.core.server;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Bounded, keyed off-thread work for pure generation. A poll that schedules submits an unknown key
 * and reports WAITING until its result is retained, then READY. A poll that does not schedule is
 * READY at once: an unknown key leaves the caller to generate synchronously as before, and a key in
 * flight is run on the caller's thread if no worker has started it, or awaited if one has. Results
 * are pure functions of their key, so eviction, failure or duplicated work only costs time; no
 * caller is ever told that work in flight is absent.
 */
final class GenerationQueue<K, V> {
    enum State { READY, WAITING }

    private static final Object FAILED = new Object();

    private final Executor executor;
    private final Function<K, V> work;
    private final Consumer<Throwable> failures;
    private final int capacity;
    // Insertion order is submission order; guarded by this. In-flight keys map to their FutureTask.
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

    @SuppressWarnings("unchecked")
    State poll(K key, boolean schedule) {
        FutureTask<Void> task;
        synchronized (this) {
            Object entry = entries.get(key);
            if (entry instanceof FutureTask) {
                if (schedule) return State.WAITING;
                task = (FutureTask<Void>) entry;
            } else {
                if (entry != null || !schedule || closed) return State.READY;
                if (running >= capacity) return State.WAITING;
                task = new FutureTask<>(() -> run(key), null);
                entries.put(key, task);
                running++;
            }
        }
        if (schedule) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException rejected) {
                task.cancel(false);
                finish(key, FAILED);
            }
            return State.WAITING;
        }
        // Not started by a worker: run it here. Started: wait for the worker. Done: return at once.
        task.run();
        try {
            task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException ignored) {
            // run() reported the failure; the caller finds no result and generates now.
        }
        return State.READY;
    }

    /** Removes and returns the retained result; null when there is none and the caller generates now. */
    @Nullable
    @SuppressWarnings("unchecked")
    synchronized V take(K key) {
        Object entry = entries.get(key);
        if (entry == null || entry instanceof FutureTask) return null;
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
            if (!(it.next() instanceof FutureTask)) {
                it.remove();
                finished--;
            }
        }
    }
}
