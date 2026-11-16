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

import io.github.opencubicchunks.cubicchunks.api.client.IClientPreviewWork;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;
import io.github.opencubicchunks.cubicchunks.core.server.delivery.LivePermits;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * The client's pool for sampling the terrain preview (docs/CUBE-SOURCE.md, section 5): the
 * counterpart of the server's {@code PreviewPool}. Its threads exist while a preview is attached:
 * they start with the first job and stop when the preview is withdrawn or replaced, so none
 * outlives a world. Each thread keeps one sampler of the attached preview. As many jobs run at once
 * as the governor's permits allow.
 */
final class ClientPreviewWork implements IClientPreviewWork {

    private final int size;
    private final LivePermits permits;
    private final ConcurrentLinkedQueue<Task> ended = new ConcurrentLinkedQueue<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<Thread> started = new ArrayList<>();
    @Nullable private Bound bound;
    private volatile boolean waited;
    private volatile boolean ran;

    /** One attached preview: its threads, and whether it still stands. */
    private static final class Bound {
        final ICubicTerrainPreview preview;
        final ThreadLocal<ICubicTerrainPreview.Sampler> sampler;
        @Nullable ThreadPoolExecutor threads;
        volatile boolean withdrawn;

        Bound(ICubicTerrainPreview preview) {
            this.preview = preview;
            this.sampler = ThreadLocal.withInitial(preview::createTerrainPreviewSampler);
        }
    }

    ClientPreviewWork(int size, int permitsAtStart) {
        this.size = Math.max(1, size);
        this.permits = new LivePermits(Math.max(1, Math.min(this.size, permitsAtStart)));
    }

    /** {@code clientPreviewThreads}: -1 is a quarter of the processors, 1 to 4. */
    static int size(int configured, int processors) {
        return configured < 0 ? Math.max(1, Math.min(4, processors / 4)) : Math.max(1, Math.min(16, configured));
    }

    /**
     * Follows the attached preview. When it is another than before, or none, the tasks of the old
     * one are withdrawn and its threads stopped. Client thread.
     */
    void bind(@Nullable ICubicTerrainPreview preview) {
        Bound old = bound;
        if (old != null && old.preview == preview || old == null && preview == null) {
            return;
        }
        if (old != null) {
            old.withdrawn = true;
            if (old.threads != null) {
                // Waiting tasks are withdrawn where they stand; running ones see the interrupt.
                for (Runnable waiting : old.threads.shutdownNow()) {
                    ((Running) waiting).task.end(WITHDRAWN, null);
                }
            }
        }
        // What the old preview's jobs finished is not handed back.
        ended.clear();
        bound = preview == null ? null : new Bound(preview);
    }

    @Override
    public int threads() {
        return size;
    }

    @Override
    public int running() {
        return permits.permits();
    }

    @Override
    public Task submit(long priority, Job job) {
        Bound now = bound;
        Task task = new Task(job, now);
        if (now == null) {
            task.end(WITHDRAWN, null);
            return task;
        }
        if (now.threads == null) {
            AtomicInteger count = new AtomicInteger();
            // Like the server's pool: the threads stay while the preview does, so a generator's
            // scratch and caches stay warm between bursts of tiles; idle daemons below normal priority.
            now.threads = new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new PriorityBlockingQueue<>(),
                    work -> {
                        Thread thread = new Thread(work, "Cubature client preview " + count.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY + 1);
                        synchronized (started) {
                            started.add(thread);
                        }
                        return thread;
                    });
        }
        now.threads.execute(new Running(task, priority, sequence.getAndIncrement()));
        return task;
    }

    @Nullable
    @Override
    public Task poll() {
        for (Task task = ended.poll(); task != null; task = ended.poll()) {
            // A task cancelled, or whose preview went, after it ended is not handed back either.
            if (task.bound == bound && !task.dropped) {
                return task;
            }
        }
        return null;
    }

    /** Sets how many jobs may run at once, as the governor decides. */
    void permits(int count) {
        permits.set(Math.max(1, Math.min(size, count)));
    }

    /** Whether a job waited for a permit, and whether one ran, since the last call. */
    boolean takeWaited() {
        boolean was = waited;
        waited = false;
        return was;
    }

    boolean takeRan() {
        boolean was = ran || permits.active() > 0;
        ran = false;
        return was;
    }

    /** For tests: the threads this pool has started that are still alive. */
    int liveThreads() {
        int live = 0;
        synchronized (started) {
            started.removeIf(thread -> !thread.isAlive());
            live = started.size();
        }
        return live;
    }

    /** A queued task in the executor's order: priority, then arrival. */
    private final class Running implements Runnable, Comparable<Running> {
        final Task task;
        final long priority;
        final int order;

        Running(Task task, long priority, int order) {
            this.task = task;
            this.priority = priority;
            this.order = order;
        }

        @Override
        public int compareTo(Running other) {
            int byPriority = Long.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Integer.compare(order, other.order);
        }

        @Override
        public void run() {
            Bound of = task.bound;
            if (of.withdrawn) {
                task.end(WITHDRAWN, null);
                return;
            }
            if (task.cancelled()) {
                return;
            }
            boolean held = false;
            try {
                if (permits.active() >= permits.permits()) {
                    waited = true;
                }
                permits.acquire();
                held = true;
                if (!task.start()) {
                    return;
                }
                ran = true;
                task.job.run(of.sampler.get(), task);
                finish(DONE, null);
            } catch (TerrainPreviewWithdrawnException | InterruptedException gone) {
                task.end(WITHDRAWN, null);
            } catch (Throwable failure) {
                finish(of.withdrawn ? WITHDRAWN : FAILED, failure);
            } finally {
                if (held) {
                    permits.release();
                }
            }
        }

        private void finish(int state, @Nullable Throwable failure) {
            if (task.bound.withdrawn) {
                task.end(WITHDRAWN, null);
            } else if (task.end(state, failure)) {
                ended.add(task);
            }
        }
    }

    private final class Task implements IClientPreviewWork.Task {
        final Job job;
        @Nullable final Bound bound;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        @Nullable private volatile Throwable failure;
        /** Cancelled once it had ended: still not handed back. */
        volatile boolean dropped;

        Task(Job job, @Nullable Bound bound) {
            this.job = job;
            this.bound = bound;
        }

        boolean start() {
            return state.compareAndSet(QUEUED, RUNNING);
        }

        /** Ends the task unless it is cancelled or ended already. */
        boolean end(int to, @Nullable Throwable thrown) {
            failure = thrown;
            return state.compareAndSet(RUNNING, to) || state.compareAndSet(QUEUED, to);
        }

        @Override
        public Job job() {
            return job;
        }

        @Override
        public int state() {
            return state.get();
        }

        @Override
        public void cancel() {
            if (!state.compareAndSet(QUEUED, CANCELLED) && !state.compareAndSet(RUNNING, CANCELLED)) {
                dropped = true;
            }
        }

        @Override
        public boolean cancelled() {
            int now = state.get();
            return now == CANCELLED || now == WITHDRAWN || bound == null || bound.withdrawn;
        }

        @Nullable
        @Override
        public Throwable failure() {
            return failure;
        }

        @Override
        public void checkpoint() throws InterruptedException {
            permits.checkpoint();
        }
    }
}
