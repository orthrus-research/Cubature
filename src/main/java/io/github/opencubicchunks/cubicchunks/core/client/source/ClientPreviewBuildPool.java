/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.client.source;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * The bounded pool a client terrain preview builds on ({@code ClientPreviewInputs.buildPool()}):
 * its own small {@link ForkJoinPool}, 1 or 2 daemon threads just above the lowest priority, made
 * for one preview and shut down with it. It is never the preview pool's threads and takes no
 * preview-pool permit, so a sampling job that waits on a build split over it waits on other
 * threads, which a join helps rather than blocks: it cannot deadlock on the pool it runs on.
 */
final class ClientPreviewBuildPool {

    private final ForkJoinPool pool;
    private final int threads;

    ClientPreviewBuildPool(int threads, String name) {
        this.threads = Math.max(1, Math.min(2, threads));
        AtomicInteger counter = new AtomicInteger();
        this.pool = new ForkJoinPool(this.threads, owner -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
            thread.setName("Cubature client preview build " + name + " #" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        }, null, false);
    }

    /** 1 or 2: half the preview pool's threads, never more than 2. */
    static int size(int previewThreads) {
        return Math.max(1, Math.min(2, previewThreads / 2));
    }

    ForkJoinPool pool() {
        return pool;
    }

    int threads() {
        return threads;
    }

    /** Threads running build work now, for the governor: work that ran. */
    int active() {
        return pool.getActiveThreadCount();
    }

    /**
     * Shuts the pool down after its preview was withdrawn: work queued is dropped and work running
     * is interrupted. The preview is revoked first by the caller, so no sampler starts a build here
     * after this.
     *
     * @param waitMillis how long to wait for the threads to end; 0 not at all
     * @return whether the threads ended within the wait
     */
    boolean shutdown(long waitMillis) {
        int queued = pool.getQueuedSubmissionCount() + (int) Math.min(Integer.MAX_VALUE, pool.getQueuedTaskCount());
        int running = pool.getActiveThreadCount();
        pool.shutdownNow();
        CubicChunks.LOGGER.info("Client preview build pool stopped: {} threads told to stop, {} queued tasks dropped, "
                + "{} running interrupted", threads, queued, running);
        PoolStopCheck.INSTANCE.stopped("Client preview build pool", this::liveThreads, System.nanoTime());
        if (waitMillis <= 0) {
            return pool.isTerminated();
        }
        try {
            return pool.awaitTermination(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Worker threads not yet ended: 0 once the pool has terminated. */
    int liveThreads() {
        return pool.isTerminated() ? 0 : pool.getPoolSize();
    }

    boolean isShutdown() {
        return pool.isShutdown();
    }

    /** A preview and the build pool it was made with, closed in that order. */
    static final class Owned {
        @Nullable final ClientPreviewBuildPool build;

        Owned(@Nullable ClientPreviewBuildPool build) {
            this.build = build;
        }

        /** Revokes the preview, then shuts its build pool down: no build starts for a preview that is gone. */
        void close(Runnable revokePreview) {
            try {
                revokePreview.run();
            } finally {
                if (build != null) {
                    build.shutdown(0);
                }
            }
        }
    }
}
