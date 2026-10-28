package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Predicate;

/** Owner-thread scoped disposal; a timeout is not permission to close a live reader's backend. */
final class AsyncTaskQuiescence {
    private AsyncTaskQuiescence() { }

    static <K,T extends AsyncIOProvider<?>> void select(List<Registration> out, Map<K,T> tasks,
            Predicate<K> belongs, ThreadPoolExecutor executor) {
        for (Map.Entry<K,T> entry : tasks.entrySet()) {
            if (belongs.test(entry.getKey())) {
                K key = entry.getKey();
                T task = entry.getValue();
                out.add(new Registration(task, executor, () -> tasks.remove(key, task)));
            }
        }
    }

    static void cancelAndAwait(List<Registration> tasks, long timeoutNanos) throws IOException {
        checkNotPublishing(tasks);
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            for (Registration item : tasks) item.task.cancelBeforeStart(item.executor);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Interrupted before world reader close");
            for (Registration item : tasks) {
                if (!item.task.awaitExecutionStoppedUntil(deadline)) {
                    throw new IOException("Timed out waiting for closing world's cube/column readers; storage remains open");
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Interrupted waiting for closing world's readers; storage remains open");
            failure.initCause(ex);
            throw failure;
        } catch (IllegalStateException ex) {
            throw new IOException("Cannot quiesce closing world's readers; storage remains open", ex);
        } finally {
            for (Registration item : tasks) {
                if (item.task.isExecutionStopped()) {
                    item.remove.run();
                    item.task.cleanup();
                }
            }
        }
    }
    /** This must precede the closing-world tombstone as well as cancellation. */
    static void checkNotPublishing(List<Registration> tasks) throws IOException {
        for (Registration item : tasks) {
            if (item.task.isCompleting()) {
                throw new IOException("Cannot close storage during owner native publication; retry after its callbacks finish");
            }
        }
    }

    static final class Registration {
        final AsyncIOProvider<?> task;
        final ThreadPoolExecutor executor;
        final Runnable remove;
        Registration(AsyncIOProvider<?> task, ThreadPoolExecutor executor, Runnable remove) {
            this.task = task;
            this.executor = executor;
            this.remove = remove;
        }
    }
}
