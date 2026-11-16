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

import io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.core.server.delivery.LivePermits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

import javax.annotation.Nullable;

/**
 * The client's pool for work on cube snapshots (docs/CUBE-SOURCE.md, section 5). Waiting tasks sit
 * in a heap on the client thread and hold no snapshot; {@link #pump} admits as many as the shared
 * permits and a lookahead of one per thread leave room for, taking their snapshots then, and hands
 * them to the threads in that order. Each pump also checks the admitted tasks against the cubes as
 * they stand now and marks stale the ones whose mask says so. The threads exist while a world is
 * attached and a job has been handed in.
 */
final class ClientBuildWork implements IClientBuildWork {

    /** What the pool reads of the attached world, on the client thread. */
    interface Cubes {
        boolean isLoaded(int x, int y, int z);

        ICubeSnapshot snapshot(int x, int y, int z, boolean centerOnly);

        long version(int x, int y, int z);

        long blockVersion(int x, int y, int z);

        long lightVersion(int x, int y, int z);
    }

    private final int size;
    private final int lookahead;
    private final LivePermits permits;
    private final Heap heap = new Heap();
    /** Tasks holding or having held a snapshot and not yet taken back, client thread. */
    private final List<BuildTask<?>> admitted = new ArrayList<>();
    private final ConcurrentLinkedQueue<BuildTask<?>> ended = new ConcurrentLinkedQueue<>();
    private final List<Thread> started = new ArrayList<>();
    @Nullable private Cubes cubes;
    @Nullable private ThreadPoolExecutor threads;
    private long sequence;
    private volatile boolean waited;
    private volatile boolean ran;

    ClientBuildWork(int size, LivePermits permits) {
        this.size = Math.max(1, size);
        this.lookahead = this.size;
        this.permits = permits;
    }

    /** {@code clientBuildThreads}: -1 is a quarter of the processors, 1 to 4. */
    static int size(int configured, int processors) {
        return configured < 0 ? Math.max(1, Math.min(4, processors / 4)) : Math.max(1, Math.min(16, configured));
    }

    /**
     * Follows the attached world. When it is another than before, or none, every task of the old one
     * is dropped, its snapshots released and its threads stopped. Client thread.
     */
    void bind(@Nullable Cubes now) {
        if (now == cubes) {
            return;
        }
        while (heap.size() > 0) {
            heap.poll().end(DROPPED);
        }
        for (BuildTask<?> task : admitted) {
            task.drop();
        }
        admitted.clear();
        if (threads != null) {
            // Waiting runs never start: their snapshots are released here. Running jobs see the interrupt.
            for (Runnable waiting : threads.shutdownNow()) {
                ((Run) waiting).task.releaseSnapshot();
            }
            threads = null;
        }
        ended.clear();
        cubes = now;
    }

    /** Marks stale what moved on, and admits waiting tasks while there is room. Client thread. */
    void pump() {
        Cubes now = cubes;
        if (now == null) {
            return;
        }
        // Room is counted by the snapshots still held, not by state: a task that ended or was cancelled
        // keeps its snapshot until its thread lets go of it, and it counts until then.
        int holding = 0;
        for (int i = admitted.size() - 1; i >= 0; i--) {
            BuildTask<?> task = admitted.get(i);
            int state = task.state();
            if (state == ADMITTED || state == DONE || state == FAILED) {
                if (task.staleIn(now)) {
                    task.end(STALE);
                    state = STALE;
                }
            }
            if (task.holdsSnapshot()) {
                holding++;
            } else if (state != ADMITTED && state != DONE && state != FAILED) {
                admitted.remove(i);
            }
        }
        int room = permits.permits() + lookahead - holding;
        while (room > 0 && heap.size() > 0) {
            BuildTask<?> task = heap.poll();
            if (!now.isLoaded(task.x, task.y, task.z)) {
                task.end(STALE);
                continue;
            }
            task.admit(now);
            admitted.add(task);
            executor().execute(new Run(task));
            room--;
        }
    }

    private ThreadPoolExecutor executor() {
        if (threads == null) {
            AtomicInteger count = new AtomicInteger();
            threads = new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), work -> {
                Thread thread = new Thread(work, "Cubature client build " + count.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY + 1);
                synchronized (started) {
                    started.removeIf(other -> !other.isAlive());
                    started.add(thread);
                }
                return thread;
            });
        }
        return threads;
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
    public int queued() {
        return heap.size();
    }

    @Override
    public int admitted() {
        int holding = 0;
        for (BuildTask<?> task : admitted) {
            if (task.holdsSnapshot()) {
                holding++;
            }
        }
        return holding;
    }

    @Override
    public int finished() {
        int waiting = 0;
        for (BuildTask<?> task : admitted) {
            int state = task.state();
            if (!task.holdsSnapshot() && (state == DONE || state == FAILED)) {
                waiting++;
            }
        }
        return waiting;
    }

    @Override
    public <R> Task<R> submit(long priority, int cubeX, int cubeY, int cubeZ, boolean centerOnly, int staleOn,
            Job<R> job) {
        BuildTask<R> task = new BuildTask<>(this, priority, sequence++, cubeX, cubeY, cubeZ, centerOnly, staleOn, job);
        if (cubes == null) {
            task.end(DROPPED);
            return task;
        }
        heap.add(task);
        pump();
        return task;
    }

    @Override
    public void reprioritize(Task<?> task, long priority) {
        BuildTask<?> ours = (BuildTask<?>) task;
        if (ours.pool != this || ours.heapIndex < 0) {
            return;
        }
        ours.priority = priority;
        heap.update(ours.heapIndex);
    }

    @Override
    public void reprioritizeAll(ToLongFunction<Task<?>> priority) {
        for (int i = 0; i < heap.size(); i++) {
            BuildTask<?> task = heap.at(i);
            task.priority = priority.applyAsLong(task);
        }
        heap.rebuild();
    }

    @Nullable
    @Override
    public Task<?> poll() {
        pump();
        Cubes now = cubes;
        for (BuildTask<?> task = ended.poll(); task != null; task = ended.poll()) {
            admitted.remove(task);
            if (now == null || task.pool != this) {
                continue;
            }
            int state = task.state();
            if ((state == DONE || state == FAILED) && task.staleIn(now)) {
                task.end(STALE);
                continue;
            }
            if (state == DONE || state == FAILED) {
                return task;
            }
        }
        return null;
    }

    /** Cancels a task on the renderer's behalf. Client thread. */
    void cancel(BuildTask<?> task) {
        if (task.heapIndex >= 0) {
            heap.remove(task.heapIndex);
        }
        task.end(CANCELLED);
    }

    /** Whether a job waited for a permit, and whether one ran, since the last call. */
    boolean takeWaited() {
        boolean was = waited;
        waited = false;
        return was;
    }

    boolean takeRan() {
        boolean was = ran;
        ran = false;
        return was;
    }

    /** For tests: the threads this pool has started that are still alive. */
    int liveThreads() {
        synchronized (started) {
            started.removeIf(thread -> !thread.isAlive());
            return started.size();
        }
    }

    /** An admitted task on its thread. */
    private final class Run implements Runnable {
        final BuildTask<?> task;

        Run(BuildTask<?> task) {
            this.task = task;
        }

        @Override
        public void run() {
            boolean held = false;
            try {
                if (task.cancelled()) {
                    return;
                }
                if (permits.active() >= permits.permits()) {
                    waited = true;
                }
                permits.acquire();
                held = true;
                if (task.cancelled()) {
                    return;
                }
                if (!task.claim()) {
                    // Cancelled while it waited: the canceller released its snapshot.
                    return;
                }
                ran = true;
                task.runJob();
            } catch (InterruptedException gone) {
                task.end(DROPPED);
            } finally {
                if (held) {
                    permits.release();
                }
                task.releaseSnapshot();
                if (task.state() == DONE || task.state() == FAILED) {
                    ended.add(task);
                }
            }
        }
    }

    static final class BuildTask<R> implements Task<R> {
        final ClientBuildWork pool;
        final int x;
        final int y;
        final int z;
        final boolean centerOnly;
        final int staleOn;
        final Job<R> job;
        final long order;
        long priority;
        int heapIndex = -1;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final AtomicReference<ICubeSnapshot> snapshot = new AtomicReference<>();
        /** Set by the thread about to run the job, or by a canceller that got there first. */
        private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        /** Set by whoever releases the snapshot, so it is released once. */
        private final java.util.concurrent.atomic.AtomicBoolean releasing = new java.util.concurrent.atomic.AtomicBoolean();
        private long blockVersion = -1;
        private long lightVersion = -1;
        private volatile long centerVersion = -1;
        /** The 27 versions when admitted, by {@code (dx + 1) * 9 + (dy + 1) * 3 + dz + 1}. */
        @Nullable private long[] versions;
        @Nullable private volatile R result;
        @Nullable private volatile Throwable failure;

        BuildTask(ClientBuildWork pool, long priority, long order, int x, int y, int z, boolean centerOnly,
                int staleOn, Job<R> job) {
            this.pool = pool;
            this.priority = priority;
            this.order = order;
            this.x = x;
            this.y = y;
            this.z = z;
            this.centerOnly = centerOnly;
            this.staleOn = staleOn;
            this.job = job;
        }

        void admit(Cubes cubes) {
            snapshot.set(cubes.snapshot(x, y, z, centerOnly));
            blockVersion = cubes.blockVersion(x, y, z);
            lightVersion = cubes.lightVersion(x, y, z);
            centerVersion = cubes.version(x, y, z);
            if ((staleOn & STALE_ON_NEIGHBORS) != 0) {
                versions = new long[27];
                for (int i = 0; i < 27; i++) {
                    versions[i] = cubes.version(x + i / 9 - 1, y + i / 3 % 3 - 1, z + i % 3 - 1);
                }
            }
            state.set(ADMITTED);
        }

        /** Whether what the task read has moved on, as its mask says. Client thread. */
        boolean staleIn(Cubes cubes) {
            if (!cubes.isLoaded(x, y, z)) {
                return true;
            }
            if ((staleOn & STALE_ON_BLOCKS) != 0 && cubes.blockVersion(x, y, z) != blockVersion) {
                return true;
            }
            if ((staleOn & STALE_ON_LIGHT) != 0 && cubes.lightVersion(x, y, z) != lightVersion) {
                return true;
            }
            if (versions != null) {
                for (int i = 0; i < 27; i++) {
                    if (i != 13 && cubes.version(x + i / 9 - 1, y + i / 3 % 3 - 1, z + i % 3 - 1) != versions[i]) {
                        return true;
                    }
                }
            }
            return false;
        }

        void runJob() {
            ICubeSnapshot taken = snapshot.get();
            if (taken == null) {
                return;
            }
            // The snapshot is let go before the task ends, so an ended task never holds one.
            try {
                R value = job.run(taken, this);
                result = value;
                releaseSnapshot();
                state.compareAndSet(ADMITTED, DONE);
            } catch (InterruptedException gone) {
                releaseSnapshot();
                end(DROPPED);
            } catch (Throwable thrown) {
                failure = thrown;
                releaseSnapshot();
                state.compareAndSet(ADMITTED, FAILED);
            }
        }

        /** Whether the calling thread may run the job: false when a canceller got there first. */
        boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        boolean holdsSnapshot() {
            return snapshot.get() != null;
        }

        /** Releases the snapshot once, whoever gets here first. */
        void releaseSnapshot() {
            ICubeSnapshot held = snapshot.get();
            // The reference is cleared only once release() has returned, so the pool counts a snapshot
            // as held until it truly is not, and the bound on held snapshots is exact.
            if (held != null && releasing.compareAndSet(false, true)) {
                held.release();
                snapshot.set(null);
            }
        }

        /** Ends the task unless it already ended for good. */
        void end(int to) {
            for (int now = state.get(); now == QUEUED || now == ADMITTED || now == DONE || now == FAILED;
                    now = state.get()) {
                if (state.compareAndSet(now, to)) {
                    // Queued or ended, no thread holds it; admitted but not yet started, its thread will not
                    // start it. Either way it is released here. A running job lets go when it returns.
                    if (now != ADMITTED || claim()) {
                        releaseSnapshot();
                    }
                    return;
                }
            }
        }

        /** The world went: an admitted task's thread releases its snapshot; one that ended has none. */
        void drop() {
            end(DROPPED);
        }

        @Override public int cubeX() { return x; }

        @Override public int cubeY() { return y; }

        @Override public int cubeZ() { return z; }

        @Override public long priority() { return priority; }

        @Override public int state() { return state.get(); }

        @Override
        public void cancel() {
            pool.cancel(this);
        }

        @Override
        public boolean cancelled() {
            int now = state.get();
            return now == CANCELLED || now == STALE || now == DROPPED;
        }

        @Override public long snapshotVersion() { return centerVersion; }

        @Nullable @Override public R result() { return result; }

        @Nullable @Override public Throwable failure() { return failure; }

        @Override
        public void checkpoint() throws InterruptedException {
            pool.permits.checkpoint();
        }
    }

    /** A min-heap of waiting tasks by priority, then order of arrival, that knows where each task is. */
    static final class Heap {
        private BuildTask<?>[] tasks = new BuildTask<?>[64];
        private int size;

        int size() {
            return size;
        }

        BuildTask<?> at(int index) {
            return tasks[index];
        }

        void add(BuildTask<?> task) {
            if (size == tasks.length) {
                tasks = Arrays.copyOf(tasks, size * 2);
            }
            tasks[size] = task;
            task.heapIndex = size;
            size++;
            up(task.heapIndex);
        }

        BuildTask<?> poll() {
            BuildTask<?> first = tasks[0];
            remove(0);
            return first;
        }

        void remove(int index) {
            BuildTask<?> gone = tasks[index];
            size--;
            if (index != size) {
                tasks[index] = tasks[size];
                tasks[index].heapIndex = index;
                tasks[size] = null;
                update(index);
            } else {
                tasks[size] = null;
            }
            gone.heapIndex = -1;
        }

        /** Restores order after the task at this index changed priority. */
        void update(int index) {
            down(up(index));
        }

        /** Restores order after any number of priorities changed. */
        void rebuild() {
            for (int i = size / 2 - 1; i >= 0; i--) {
                down(i);
            }
        }

        private static boolean before(BuildTask<?> a, BuildTask<?> b) {
            return a.priority != b.priority ? a.priority < b.priority : a.order < b.order;
        }

        private int up(int index) {
            while (index > 0) {
                int parent = (index - 1) / 2;
                if (!before(tasks[index], tasks[parent])) {
                    break;
                }
                swap(index, parent);
                index = parent;
            }
            return index;
        }

        private void down(int index) {
            while (true) {
                int left = 2 * index + 1;
                if (left >= size) {
                    return;
                }
                int child = left + 1 < size && before(tasks[left + 1], tasks[left]) ? left + 1 : left;
                if (!before(tasks[child], tasks[index])) {
                    return;
                }
                swap(index, child);
                index = child;
            }
        }

        private void swap(int a, int b) {
            BuildTask<?> task = tasks[a];
            tasks[a] = tasks[b];
            tasks[b] = task;
            tasks[a].heapIndex = a;
            tasks[b].heapIndex = b;
        }
    }
}
