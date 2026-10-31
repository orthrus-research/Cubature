package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A permit count that may change at any moment: at most {@code permits} holders run at once, and a holder over the
 * count gives its place up at its next checkpoint and waits there until it fits again. A cut therefore takes effect
 * within one checkpoint interval, without interrupting anyone; a raise wakes waiting holders at once.
 */
public final class LivePermits {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition fits = lock.newCondition();
    private volatile int permits, active;

    public LivePermits(int permits) {
        this.permits = Math.max(0, permits);
    }

    /** Waits until a holder fits, then holds a permit. */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (active >= permits) fits.await();
            active++;
        } finally {
            lock.unlock();
        }
    }

    public void release() {
        lock.lock();
        try {
            active--;
            fits.signal();
        } finally {
            lock.unlock();
        }
    }

    /** Between units of work: a holder over the count waits until it fits again. */
    public void checkpoint() throws InterruptedException {
        if (active <= permits) return;
        lock.lockInterruptibly();
        try {
            if (active <= permits) return;
            active--;
            fits.signal();
            while (active >= permits) fits.await();
            active++;
        } finally {
            lock.unlock();
        }
    }

    public void set(int permits) {
        lock.lock();
        try {
            this.permits = Math.max(0, permits);
            fits.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int permits() {
        return permits;
    }

    /** Holders running now, those waiting at a checkpoint excluded. */
    public int active() {
        return active;
    }
}
