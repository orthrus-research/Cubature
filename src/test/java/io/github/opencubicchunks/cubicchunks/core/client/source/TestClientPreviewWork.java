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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.client.IClientPreviewWork;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestClientPreviewWork {

    /** A preview that counts its samplers and remembers which thread each was made on. */
    private static final class Preview implements ICubicTerrainPreview {
        final AtomicInteger samplers = new AtomicInteger();
        final Set<Thread> makers = Collections.synchronizedSet(new HashSet<>());

        @Override public Sampler createTerrainPreviewSampler() {
            samplers.incrementAndGet();
            makers.add(Thread.currentThread());
            return (x, z, consumer) -> { };
        }
    }

    private static IClientPreviewWork.Task take(ClientPreviewWork work) throws InterruptedException {
        for (int i = 0; i < 2000; i++) {
            IClientPreviewWork.Task task = work.poll();
            if (task != null) {
                return task;
            }
            Thread.sleep(1);
        }
        return null;
    }

    private static void awaitNoThreads(ClientPreviewWork work) throws InterruptedException {
        for (int i = 0; i < 2000 && work.liveThreads() > 0; i++) {
            Thread.sleep(1);
        }
        assertEquals(0, work.liveThreads());
    }

    @Test public void jobsRunLowestPriorityFirstAndInOrderOfArrivalWithinOne() throws Exception {
        ClientPreviewWork work = new ClientPreviewWork(1, 1);
        work.bind(new Preview());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        work.submit(0, (sampler, task) -> {
            entered.countDown();
            gate.await();
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // The one thread is held; these wait and are sorted.
        work.submit(50, (sampler, task) -> order.add("far"));
        work.submit(10, (sampler, task) -> order.add("near, first"));
        work.submit(30, (sampler, task) -> order.add("middle"));
        work.submit(10, (sampler, task) -> order.add("near, second"));
        gate.countDown();
        for (int i = 0; i < 5; i++) {
            IClientPreviewWork.Task ended = take(work);
            assertNotNull(ended);
            assertEquals(IClientPreviewWork.DONE, ended.state());
        }
        assertEquals(java.util.Arrays.asList("near, first", "near, second", "middle", "far"), order);
        assertNull(work.poll());
        work.bind(null);
        awaitNoThreads(work);
    }

    @Test public void aCancelledTaskNeverRunsAndNeverComesBack() throws Exception {
        ClientPreviewWork work = new ClientPreviewWork(1, 1);
        work.bind(new Preview());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        IClientPreviewWork.Task running = work.submit(0, (sampler, task) -> {
            entered.countDown();
            gate.await();
            ran.incrementAndGet();
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        IClientPreviewWork.Task waiting = work.submit(1, (sampler, task) -> ran.addAndGet(100));
        IClientPreviewWork.Task last = work.submit(2, (sampler, task) -> ran.addAndGet(1000));
        waiting.cancel();
        assertEquals(IClientPreviewWork.CANCELLED, waiting.state());
        // Cancelled while running: its job is told, and its end is not handed back.
        running.cancel();
        assertTrue(running.cancelled());
        gate.countDown();
        IClientPreviewWork.Task ended = take(work);
        assertSame("Only the task that was not cancelled comes back", last, ended);
        assertEquals(1001, ran.get());
        assertEquals(IClientPreviewWork.CANCELLED, running.state());
        assertNull(work.poll());
        // Cancelled after it ended but before it was taken: not handed back either.
        IClientPreviewWork.Task late = work.submit(0, (sampler, task) -> { });
        for (int i = 0; i < 2000 && late.state() != IClientPreviewWork.DONE; i++) {
            Thread.sleep(1);
        }
        assertEquals(IClientPreviewWork.DONE, late.state());
        late.cancel();
        assertNull(work.poll());
        work.bind(null);
        awaitNoThreads(work);
    }

    @Test public void aWithdrawnPreviewEndsItsTasksAndItsThreads() throws Exception {
        ClientPreviewWork work = new ClientPreviewWork(2, 2);
        Preview first = new Preview();
        work.bind(first);
        CountDownLatch entered = new CountDownLatch(2);
        AtomicInteger ranAfter = new AtomicInteger();
        List<IClientPreviewWork.Task> held = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            held.add(work.submit(0, (sampler, task) -> {
                entered.countDown();
                // Until the pool interrupts it.
                new CountDownLatch(1).await();
            }));
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        IClientPreviewWork.Task queued = work.submit(5, (sampler, task) -> ranAfter.incrementAndGet());
        IClientPreviewWork.Task done = work.submit(-1, (sampler, task) -> { });
        work.bind(null);
        // The stop is counted as it is logged: two threads, two waiting tasks withdrawn, two running interrupted.
        org.junit.Assert.assertArrayEquals(new int[] {2, 2, 2}, work.lastStop);
        assertEquals("A waiting task is withdrawn where it stands", IClientPreviewWork.WITHDRAWN, queued.state());
        assertEquals(IClientPreviewWork.WITHDRAWN, done.state());
        awaitNoThreads(work);
        for (IClientPreviewWork.Task task : held) {
            assertEquals("A running task is interrupted and withdrawn", IClientPreviewWork.WITHDRAWN, task.state());
            assertTrue(task.cancelled());
        }
        assertEquals(0, ranAfter.get());
        assertNull("Nothing of a withdrawn preview comes back", work.poll());
        IClientPreviewWork.Task none = work.submit(0, (sampler, task) -> ranAfter.incrementAndGet());
        assertEquals("With no preview a task is withdrawn at once", IClientPreviewWork.WITHDRAWN, none.state());
        assertEquals(0, work.liveThreads());
    }

    @Test public void aReplacedPreviewGetsNewThreadsAndSamplers() throws Exception {
        ClientPreviewWork work = new ClientPreviewWork(2, 2);
        Preview first = new Preview();
        Preview second = new Preview();
        work.bind(first);
        for (int i = 0; i < 8; i++) {
            work.submit(i, (sampler, task) -> { });
        }
        for (int i = 0; i < 8; i++) {
            assertNotNull(take(work));
        }
        assertTrue("One sampler a thread, not one a job", first.samplers.get() >= 1 && first.samplers.get() <= 2);
        Set<Thread> firstThreads = new HashSet<>(first.makers);
        // A job that ended under the first preview and was not yet taken is dropped by the change.
        IClientPreviewWork.Task stale = work.submit(0, (sampler, task) -> { });
        for (int i = 0; i < 2000 && stale.state() != IClientPreviewWork.DONE; i++) {
            Thread.sleep(1);
        }
        work.bind(second);
        assertNull(work.poll());
        IClientPreviewWork.Task fresh = work.submit(0, (sampler, task) -> { });
        assertSame(fresh, take(work));
        assertEquals(1, second.samplers.get());
        for (Thread maker : second.makers) {
            assertFalse("The second preview is not sampled on the first one's threads", firstThreads.contains(maker));
        }
        for (int i = 0; i < 2000; i++) {
            boolean alive = false;
            for (Thread thread : firstThreads) {
                alive |= thread.isAlive();
            }
            if (!alive) {
                break;
            }
            Thread.sleep(1);
        }
        for (Thread thread : firstThreads) {
            assertFalse("The first preview's threads have ended", thread.isAlive());
        }
        work.bind(null);
        awaitNoThreads(work);
    }

    @Test public void aFailingJobComesBackWithWhatItThrewAndAWithdrawnOneDoesNot() throws Exception {
        ClientPreviewWork work = new ClientPreviewWork(1, 1);
        work.bind(new Preview());
        IllegalStateException thrown = new IllegalStateException("degenerate");
        IClientPreviewWork.Task withdrawn = work.submit(0, (sampler, task) -> {
            throw new TerrainPreviewWithdrawnException();
        });
        IClientPreviewWork.Task failing = work.submit(1, (sampler, task) -> {
            throw thrown;
        });
        IClientPreviewWork.Task ended = take(work);
        assertSame(failing, ended);
        assertEquals(IClientPreviewWork.FAILED, ended.state());
        assertSame(thrown, ended.failure());
        assertEquals(IClientPreviewWork.WITHDRAWN, withdrawn.state());
        assertNull(work.poll());
        work.bind(null);
        awaitNoThreads(work);
    }

    @Test public void noMoreJobsRunAtOnceThanThePermits() throws Exception {
        ClientPreviewWork work = new ClientPreviewWork(4, 1);
        work.bind(new Preview());
        assertEquals(4, work.threads());
        assertEquals(1, work.running());
        AtomicInteger together = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        for (int i = 0; i < 12; i++) {
            work.submit(i, (sampler, task) -> {
                most.accumulateAndGet(together.incrementAndGet(), Math::max);
                Thread.sleep(2);
                together.decrementAndGet();
            });
        }
        for (int i = 0; i < 12; i++) {
            assertNotNull(take(work));
        }
        assertEquals(1, most.get());
        assertTrue("A job waited for the one permit", work.takeWaited());
        assertTrue(work.takeRan());
        work.permits(9);
        assertEquals("Never more than the threads", 4, work.running());
        work.permits(0);
        assertEquals("Never fewer than one", 1, work.running());
        work.bind(null);
        awaitNoThreads(work);
    }

    @Test public void theConfiguredSizeIsBounded() {
        assertEquals(1, ClientPreviewWork.size(-1, 2));
        assertEquals(2, ClientPreviewWork.size(-1, 8));
        assertEquals(4, ClientPreviewWork.size(-1, 64));
        assertEquals(3, ClientPreviewWork.size(3, 64));
        assertEquals(1, ClientPreviewWork.size(0, 64));
        assertEquals(16, ClientPreviewWork.size(40, 64));
    }
}
