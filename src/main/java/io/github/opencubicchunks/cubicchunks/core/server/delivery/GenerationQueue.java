package io.github.opencubicchunks.cubicchunks.core.server.delivery;

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

    /** State other than FutureTask's run-once ownership is guarded by the queue monitor. */
    private static final class Task {
        final FutureTask<Void> future;
        boolean claimed, retired, settled;

        Task(Consumer<Task> work) { future = new FutureTask<>(() -> work.accept(this), null); }
    }

    private final Executor executor;
    private final Function<K, V> work;
    private final Consumer<Throwable> failures;
    private final int capacity;
    // Insertion order is submission order; guarded by this. In-flight keys retain their task token.
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
        Task task;
        synchronized (this) {
            Object entry = entries.get(key);
            if (entry instanceof Task) {
                if (schedule) return State.WAITING;
                task = (Task) entry;
            } else {
                if (entry != null || !schedule || closed) return State.READY;
                if (running >= capacity) return State.WAITING;
                task = new Task(token -> run(key, token));
                entries.put(key, task);
                running++;
            }
        }
        if (schedule) {
            try {
                executor.execute(task.future);
            } catch (RejectedExecutionException rejected) {
                reject(key, task);
            }
            return State.WAITING;
        }
        // Not started by a worker: run it here. Started: wait for the worker. Done: return at once.
        task.future.run();
        try {
            task.future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException ignored) {
            // A failed or rejected attempt leaves no usable result for the synchronous caller.
        }
        return State.READY;
    }

    /** Removes and returns the retained result; null when there is none and the caller generates now. */
    @Nullable
    @SuppressWarnings("unchecked")
    synchronized V take(K key) {
        Object entry = entries.get(key);
        if (entry == null || entry instanceof Task) return null;
        entries.remove(key);
        return entry == FAILED ? null : (V) entry;
    }

    synchronized boolean saturated() { return running >= capacity; }

    synchronized int running() { return running; }

    synchronized int retained() { return entries.size() - running; }

    /** Drop obsolete output without waiting, interrupting, or releasing a pending reservation early. */
    synchronized void retire(K key) {
        Object entry = entries.get(key);
        if (entry instanceof Task task) task.retired = true;
        else entries.remove(key);
    }

    synchronized void close() {
        closed = true;
        entries.clear();
    }

    private synchronized void reject(K key, Task task) {
        // Another caller can claim or complete the future while execute() has not returned.
        if (task.claimed || task.settled) return;
        task.future.cancel(false);
        finish(key, task, FAILED);
    }

    private void run(K key, Task task) {
        boolean retired;
        synchronized (this) {
            if (task.settled) return;
            task.claimed = true;
            retired = task.retired;
        }
        Object result = FAILED;
        try {
            if (retired) return;
            V value = work.apply(key);
            if (value != null) result = value;
        } catch (RuntimeException e) {
            // A requesting caller can retry failed generation in its own context.
            failures.accept(e);
        } finally {
            finish(key, task, result);
        }
    }

    private synchronized void finish(K key, Task task, Object result) {
        if (task.settled) return;
        task.settled = true;
        running--;
        if (closed || entries.get(key) != task) return;
        if (task.retired) {
            entries.remove(key);
            return;
        }
        entries.put(key, result);
        // Retain at most capacity finished results, dropping the oldest unclaimed ones.
        int finished = entries.size() - running;
        for (Iterator<Object> it = entries.values().iterator(); finished > capacity && it.hasNext(); ) {
            if (!(it.next() instanceof Task)) {
                it.remove();
                finished--;
            }
        }
    }
}
