package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Close the stop-before-publication gap without releasing a running reader's ownership. */
final class AsyncTaskRetirement {
    private AsyncTaskRetirement() { }

    static <R> void enqueue(AsyncIOProvider<?> task, ConcurrentLinkedQueue<R> queue, R record, Runnable discardStopped) {
        if (!task.claimRetirement()) return;
        queue.add(record);
        // The worker may have stopped and purged the queue before add published this record.
        // Recheck after publication so cleanup never depends on another owner tick.
        if (task.isExecutionStopped()) {
            discardStopped.run();
            queue.remove(record);
        }
    }
}
