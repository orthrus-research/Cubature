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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TestClientPreviewBuildPool {

    @Test
    public void oneOrTwoThreadsHalfThePreviewPools() {
        assertEquals(1, ClientPreviewBuildPool.size(1));
        assertEquals(1, ClientPreviewBuildPool.size(2));
        assertEquals(1, ClientPreviewBuildPool.size(3));
        assertEquals(2, ClientPreviewBuildPool.size(4));
        assertEquals(2, ClientPreviewBuildPool.size(16));
        assertEquals(2, new ClientPreviewBuildPool(9, "test").threads());
    }

    /** A build split in halves down to single cells, each half forked and joined. */
    private static final class Split extends RecursiveTask<Long> {
        final int from;
        final int to;

        Split(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        protected Long compute() {
            if (to - from <= 1) {
                return (long) from;
            }
            int middle = (from + to) >>> 1;
            Split left = new Split(from, middle);
            left.fork();
            return new Split(middle, to).compute() + left.join();
        }
    }

    @Test
    public void aSplitBuildFinishesOnOneThreadAndOnItsOwnLowPriorityDaemons() throws Exception {
        ClientPreviewBuildPool build = new ClientPreviewBuildPool(1, "test");
        try {
            // Joins help: a build split far wider than the pool still finishes on one thread.
            assertEquals(4095L * 4096 / 2, (long) build.pool().invoke(new Split(0, 4096)));
            AtomicReference<Thread> ran = new AtomicReference<>();
            build.pool().submit(() -> ran.set(Thread.currentThread())).get(5, TimeUnit.SECONDS);
            assertTrue(ran.get().isDaemon());
            assertEquals(Thread.MIN_PRIORITY + 1, ran.get().getPriority());
            assertTrue(ran.get().getName().startsWith("Cubature client preview build test #"));
            assertTrue("Never the common pool", build.pool() != ForkJoinPool.commonPool());
        } finally {
            build.shutdown(5000);
        }
    }

    @Test
    public void aSamplingThreadThatWaitsOnABuildWaitsOnOtherThreads() throws Exception {
        // As a preview-pool job would: an outside thread submits a build and waits for it.
        ClientPreviewBuildPool build = new ClientPreviewBuildPool(1, "test");
        try {
            AtomicReference<Long> sum = new AtomicReference<>();
            Thread sampling = new Thread(() -> sum.set(build.pool().invoke(new Split(0, 1024))));
            sampling.start();
            sampling.join(5000);
            assertFalse(sampling.isAlive());
            assertEquals(1023L * 1024 / 2, (long) sum.get());
        } finally {
            build.shutdown(5000);
        }
    }

    @Test
    public void thePreviewIsRevokedBeforeItsBuildPoolStops() {
        ClientPreviewBuildPool build = new ClientPreviewBuildPool(2, "test");
        AtomicBoolean shutDownAtRevoke = new AtomicBoolean(true);
        new ClientPreviewBuildPool.Owned(build).close(() -> shutDownAtRevoke.set(build.isShutdown()));
        assertFalse("While the preview is revoked its pool still runs", shutDownAtRevoke.get());
        assertTrue("Then the pool is shut down", build.isShutdown());
    }

    @Test
    public void thePoolStopsEvenWhenRevokingThrows() {
        ClientPreviewBuildPool build = new ClientPreviewBuildPool(1, "test");
        try {
            new ClientPreviewBuildPool.Owned(build).close(() -> {
                throw new IllegalStateException("revoke");
            });
        } catch (IllegalStateException expected) {
            assertEquals("revoke", expected.getMessage());
        }
        assertTrue(build.isShutdown());
        // A borrowed preview owns no pool: closing it only revokes.
        AtomicBoolean revoked = new AtomicBoolean();
        new ClientPreviewBuildPool.Owned(null).close(() -> revoked.set(true));
        assertTrue(revoked.get());
    }

    @Test
    public void shuttingDownInterruptsRunningBuildsAndEndsTheThreads() throws Exception {
        ClientPreviewBuildPool build = new ClientPreviewBuildPool(1, "test");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        build.pool().execute(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(1, build.active());
        assertTrue("The threads end", build.shutdown(5000));
        assertTrue(interrupted.get());
        assertEquals(0, build.active());
    }
}
