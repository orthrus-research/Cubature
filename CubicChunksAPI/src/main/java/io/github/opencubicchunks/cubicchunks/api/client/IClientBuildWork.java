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
package io.github.opencubicchunks.cubicchunks.api.client;

import java.util.function.ToLongFunction;

import javax.annotation.Nullable;

/**
 * Cubic Chunks' threads for work on cube snapshots, such as building a renderer's meshes in the
 * background, so a renderer starts none of its own (docs/CUBE-SOURCE.md, section 5). They share one
 * budget with the {@linkplain IClientPreviewWork preview pool}: the two pools together never run
 * more jobs at once than the governor allows.
 *
 * <p>A task names a cube and a priority, lowest first. It waits on the client thread, in a queue
 * whose priorities can be changed cheaply as the camera moves, and holds no snapshot while it
 * waits. When a thread is about to be free the pool takes the task's snapshot, on the client
 * thread, and hands it to the job; the snapshot is released when the job ends, whatever way it
 * ends. The renderer takes finished tasks back on the client thread with {@link #poll}, which never
 * waits.
 *
 * <p>A task goes {@link #STALE} when its cube leaves, and when what it read moves on, as its
 * {@code staleOn} mask says: a running job sees {@link Task#cancelled()} and should return, and a
 * stale task never comes back from {@link #poll}. The renderer, which hears of the change through
 * its listener, hands in a new task. When the world changes every task is {@link #DROPPED}.
 *
 * <p>Every method is called on the client thread, except a {@link Job}'s, and {@link Task}'s
 * {@link Task#cancelled()} and {@link Task#checkpoint()}, which the job calls on its own thread.
 */
public interface IClientBuildWork {

    /** Waiting for a thread, with no snapshot yet. */
    int QUEUED = 0;
    /** Its snapshot is taken and it is waiting for, or has, a thread. */
    int ADMITTED = 1;
    /** Its job returned; the task comes back from {@link #poll} with {@link Task#result()}. */
    int DONE = 2;
    /** Its job threw; the task comes back from {@link #poll} with {@link Task#failure()}. */
    int FAILED = 3;
    /** Cancelled by the renderer; it never comes back from {@link #poll}. */
    int CANCELLED = 4;
    /** Its cube left, or what it read changed as its mask says; it never comes back from {@link #poll}. */
    int STALE = 5;
    /** The world it belonged to went; it never comes back from {@link #poll}. */
    int DROPPED = 6;

    /** In {@code staleOn}: the center cube's blocks or biomes changed since its snapshot. */
    int STALE_ON_BLOCKS = 1;
    /** In {@code staleOn}: the center cube's light changed since its snapshot. */
    int STALE_ON_LIGHT = 2;
    /** In {@code staleOn}: any of the 26 neighbors changed, arrived or left since the snapshot. */
    int STALE_ON_NEIGHBORS = 4;
    /** The usual mask: the center's blocks and light. A task is always stale when its cube leaves. */
    int STALE_ON_CENTER = STALE_ON_BLOCKS | STALE_ON_LIGHT;

    /** How many threads this pool has. */
    int threads();

    /** How many jobs of this pool and the preview pool together may run at once now. */
    int running();

    /** Tasks waiting without a snapshot. */
    int queued();

    /** Tasks holding a snapshot: admitted, running, or ended and not yet taken back. */
    int admitted();

    /**
     * Hands in a job for a loaded cube. A cube that is not loaded when its turn comes makes the task
     * {@link #STALE} without running.
     *
     * @param priority   lower runs first; equal priorities run in order of arrival
     * @param centerOnly whether the job needs only the cube itself ({@link ICubeSource#snapshotCenter})
     *                   rather than the cube and its neighbors ({@link ICubeSource#snapshot})
     * @param staleOn    a combination of the {@code STALE_ON_} bits, usually {@link #STALE_ON_CENTER}
     */
    <R> Task<R> submit(long priority, int cubeX, int cubeY, int cubeZ, boolean centerOnly, int staleOn, Job<R> job);

    /** Changes a waiting task's priority. An admitted or ended task is unchanged. */
    void reprioritize(Task<?> task, long priority);

    /**
     * Gives every waiting task a new priority at once, as when the camera moves: cheaper than one
     * {@link #reprioritize} a task. The function runs on the client thread, once per waiting task.
     */
    void reprioritizeAll(ToLongFunction<Task<?>> priority);

    /**
     * The next task whose job ended, {@link #DONE} or {@link #FAILED}, and that is not stale, or null
     * when there is none now. Never waits.
     */
    @Nullable
    Task<?> poll();

    /** Work on a cube snapshot. */
    @FunctionalInterface
    interface Job<R> {

        /**
         * Runs on a pool thread.
         *
         * @param snapshot the task's snapshot; it must not be kept past this call
         * @param task     this job's task: a long job asks {@link Task#cancelled()} now and then and
         *                 returns early, and may call {@link Task#checkpoint()} there
         * @return the result {@link Task#result()} gives back
         */
        R run(ICubeSnapshot snapshot, Task<R> task) throws Exception;
    }

    /** A job handed in. */
    interface Task<R> {

        int cubeX();

        int cubeY();

        int cubeZ();

        /** The priority it waits with. */
        long priority();

        /** One of {@link #QUEUED}, {@link #ADMITTED}, {@link #DONE}, {@link #FAILED}, {@link #CANCELLED}, {@link #STALE}, {@link #DROPPED}. */
        int state();

        /**
         * Cancels the task. Waiting, it leaves the queue; admitted, its snapshot is released and its
         * job, if running, sees {@link #cancelled()}. It never comes back from {@link #poll}.
         */
        void cancel();

        /** Whether the job should stop: the task was cancelled, went stale or was dropped. */
        boolean cancelled();

        /**
         * The version of the center cube in the task's snapshot, -1 before it has one. A renderer
         * keeps it with the mesh it builds.
         */
        long snapshotVersion();

        /** What the job returned, for a {@link #DONE} task. */
        @Nullable
        R result();

        /** What the job threw, for a {@link #FAILED} task. */
        @Nullable
        Throwable failure();

        /**
         * Called by a running job between parts of its work: if the governor has lowered how many
         * jobs may run, this one waits here until it fits again.
         */
        void checkpoint() throws InterruptedException;
    }
}
