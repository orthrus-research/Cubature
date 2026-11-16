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

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import javax.annotation.Nullable;

/**
 * Cubic Chunks' threads for sampling the terrain preview on a client, so a renderer starts none of
 * its own. A renderer hands in jobs with a priority, each job samples through a sampler the pool
 * keeps for the thread that runs it, and the renderer takes finished tasks back on the client
 * thread, a few a frame, without waiting.
 *
 * <p>{@link #submit}, {@link #poll} and {@link Task#cancel} are called on the client thread. A
 * {@link Job} runs on one of the pool's threads.
 *
 * <p>Tasks belong to the preview that was attached when they were handed in. When that preview is
 * withdrawn or replaced (the world changes, the client disconnects), tasks still waiting become
 * {@link #WITHDRAWN} without running, tasks that are running are interrupted, and nothing of either
 * comes back from {@link #poll}: the renderer hears of the change through
 * {@link ICubeSourceListener#terrainPreviewChanged()} and lets its own records of them go.
 */
public interface IClientPreviewWork {

    /** Waiting for a thread. */
    int QUEUED = 0;
    /** Its job is running. */
    int RUNNING = 1;
    /** Its job returned; the task comes back from {@link #poll}. */
    int DONE = 2;
    /** Its job threw; the task comes back from {@link #poll} with {@link Task#failure()}. */
    int FAILED = 3;
    /** Cancelled by the renderer; it never comes back from {@link #poll}. */
    int CANCELLED = 4;
    /** Its preview was withdrawn or replaced; it never comes back from {@link #poll}. */
    int WITHDRAWN = 5;

    /** How many threads the pool has. Fewer may run at once: see {@link #running()}. */
    int threads();

    /** How many jobs may run at once now, as the pool's governor has set it: 1 to {@link #threads()}. */
    int running();

    /**
     * Hands in a job that samples the attached world's terrain preview. Jobs run in order of
     * priority, lowest first, and in order of arrival within a priority. With no preview attached
     * the task is {@link #WITHDRAWN} at once.
     */
    Task submit(long priority, Job job);

    /**
     * The next task whose job has ended, {@link #DONE} or {@link #FAILED}, or null when there is
     * none now. Never waits.
     */
    @Nullable
    Task poll();

    /** Work that samples the preview. It keeps its own result, in memory the renderer owns. */
    @FunctionalInterface
    interface Job {

        /**
         * Runs on a pool thread.
         *
         * @param sampler a sampler of the task's preview, kept for this thread; it must not be used
         *                after the job returns or on another thread
         * @param task    this job's task: a long job asks {@link Task#cancelled()} between its rows
         *                and returns early, and may call {@link Task#checkpoint()} there
         */
        void run(ICubicTerrainPreview.Sampler sampler, Task task) throws Exception;
    }

    /** A job handed in: where it stands, and the means to cancel it. */
    interface Task {

        /** The job this task runs. */
        Job job();

        /** One of {@link #QUEUED}, {@link #RUNNING}, {@link #DONE}, {@link #FAILED}, {@link #CANCELLED}, {@link #WITHDRAWN}. */
        int state();

        /**
         * Cancels the task. Waiting, it never runs. Running, its job sees {@link #cancelled()} and
         * should return; whatever it does, the task does not come back from {@link #poll}. A task
         * that has already ended is unchanged.
         */
        void cancel();

        /** Whether the job should stop: the task was cancelled, or its preview withdrawn or replaced. */
        boolean cancelled();

        /** What the job threw, for a {@link #FAILED} task. */
        @Nullable
        Throwable failure();

        /**
         * Called by a running job between its rows: if the governor has lowered how many jobs may
         * run, this one waits here until it fits again.
         */
        void checkpoint() throws InterruptedException;
    }
}
