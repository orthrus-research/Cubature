package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/** One decode owner, including tasks which have not yet acquired their parent column. */
final class AsyncTaskExecution implements Runnable {
    private static final int WAITING = 0, QUEUED = 1, RUNNING = 2, FINISHED = 3, CANCELLED = 4;

    private final AtomicInteger state = new AtomicInteger(WAITING);
    private final ThreadPoolExecutor executor;
    private final Runnable load;
    private final Consumer<RuntimeException> rejected;
    private final Runnable stopped;
    private volatile Thread executingThread;

    AsyncTaskExecution(ThreadPoolExecutor executor, Runnable load, Consumer<RuntimeException> rejected) {
        this(executor,load,rejected,null);
    }

    AsyncTaskExecution(ThreadPoolExecutor executor, Runnable load, Consumer<RuntimeException> rejected, Runnable stopped) {
        this.executor = executor;
        this.load = load;
        this.rejected = rejected;
        this.stopped = stopped;
    }

    /** Called only after the parent dependency is available; repeated notifications are harmless. */
    void enqueue() {
        if (!state.compareAndSet(WAITING, QUEUED)) return;
        try {
            executor.execute(this);
        } catch (RuntimeException error) {
            if (state.compareAndSet(QUEUED, RUNNING)) {
                try {
                    rejected.accept(error);
                } finally {
                    finish();
                }
            } else {
                throw error;
            }
        }
    }

    @Override public void run() {
        if (state.compareAndSet(QUEUED, RUNNING)) execute();
    }

    /** A failed queue removal is not evidence that a dependency-waiting task is running. */
    void runOrAwait() {
        for (;;) {
            int current = state.get();
            if (current == FINISHED) return;
            if (current == CANCELLED) throw new CancellationException("Cube/column load was cancelled before execution");
            if (current == RUNNING) {
                if (executingThread == Thread.currentThread()) {
                    throw new IllegalStateException("Reentrant cube/column decode cannot wait on its own execution before publication");
                }
                awaitFinished();
                return;
            }
            if (state.compareAndSet(current, RUNNING)) {
                executor.remove(this);
                execute();
                return;
            }
        }
    }

    /** Cancellation never releases a column still used by a running decoder. */
    boolean cancelBeforeStart() {
        for (;;) {
            int current = state.get();
            if (current != WAITING && current != QUEUED) return false;
            if (state.compareAndSet(current, CANCELLED)) {
                executor.remove(this);
                return true;
            }
        }
    }

    private void execute() {
        executingThread = Thread.currentThread();
        try {
            load.run();
        } finally {
            executingThread = null;
            finish();
        }
    }

    private void finish() {
        synchronized(this){state.set(FINISHED);notifyAll();}
        if(stopped!=null)stopped.run();
    }

    private synchronized void awaitFinished() {
        while (state.get() == RUNNING) {
            try {
                wait();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for cube/column decode", error);
            }
        }
    }

    boolean isStopped() { int value=state.get();return value==FINISHED||value==CANCELLED; }

    synchronized boolean awaitStoppedUntil(long deadline) throws InterruptedException {
        if(executingThread==Thread.currentThread())throw new IllegalStateException("Cannot close storage from its own running decoder");
        while(!isStopped()){
            long remaining=deadline-System.nanoTime();if(remaining<=0)return false;
            TimeUnit.NANOSECONDS.timedWait(this,remaining);
        }
        return true;
    }
}
