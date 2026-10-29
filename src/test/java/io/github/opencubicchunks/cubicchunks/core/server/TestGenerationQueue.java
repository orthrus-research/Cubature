package io.github.opencubicchunks.cubicchunks.core.server;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TestGenerationQueue {
    /** Runs submitted work only when told to, so every interleaving is explicit. */
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void execute(Runnable command) { pending.add(command); }
        void runAll() { while (!pending.isEmpty()) pending.poll().run(); }
    }

    private static GenerationQueue<Integer, String> queue(Executor executor, int capacity, List<Throwable> failures) {
        return new GenerationQueue<>(executor, key -> {
            if (key < 0) throw new IllegalStateException("bad key " + key);
            return "v" + key;
        }, failures::add, capacity);
    }

    @Test public void aPollThatDoesNotScheduleLeavesAnUnknownKeyToTheCallerAndSubmitsNothing() {
        ManualExecutor executor = new ManualExecutor();
        GenerationQueue<Integer, String> q = queue(executor, 4, new ArrayList<>());
        assertEquals(GenerationQueue.State.READY, q.poll(1, false));
        assertTrue(executor.pending.isEmpty());
        assertNull(q.take(1));
    }

    @Test public void aPollThatDoesNotScheduleRunsQueuedWorkHereOnce() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        GenerationQueue<Integer, String> q = new GenerationQueue<>(executor, key -> "v" + key + "#" + calls.incrementAndGet(),
                failure -> fail("no failure expected"), 4);
        assertEquals(GenerationQueue.State.WAITING, q.poll(7, true));
        assertEquals(1, executor.pending.size());
        // Queued but not started: the caller runs it rather than being told it is absent.
        assertEquals(GenerationQueue.State.READY, q.poll(7, false));
        assertEquals("v7#1", q.take(7));
        executor.runAll();
        assertEquals(1, calls.get());
        assertEquals(0, q.running());
        assertEquals(0, q.retained());
    }

    @Test public void aPollThatDoesNotScheduleWaitsForAWorkerAlreadyRunningTheKey() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        GenerationQueue<Integer, String> q = new GenerationQueue<>(command -> new Thread(command).start(), key -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "v" + key;
        }, failure -> fail("no failure expected"), 4);
        assertEquals(GenerationQueue.State.WAITING, q.poll(3, true));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            release.countDown();
        });
        releaser.start();
        assertEquals(GenerationQueue.State.READY, q.poll(3, false));
        assertEquals("v3", q.take(3));
        assertEquals(1, calls.get());
        releaser.join();
    }

    @Test public void scheduledKeysWaitUntilTheirResultIsRetainedAndAreTakenOnce() {
        ManualExecutor executor = new ManualExecutor();
        GenerationQueue<Integer, String> q = queue(executor, 4, new ArrayList<>());
        assertEquals(GenerationQueue.State.WAITING, q.poll(7, true));
        assertEquals(GenerationQueue.State.WAITING, q.poll(7, true));
        assertEquals(1, executor.pending.size());
        assertNull(q.take(7));
        executor.runAll();
        assertEquals(GenerationQueue.State.READY, q.poll(7, true));
        assertEquals("v7", q.take(7));
        assertNull(q.take(7));
    }

    @Test public void capacityBoundsRunningWorkAndRetainedResults() {
        ManualExecutor executor = new ManualExecutor();
        GenerationQueue<Integer, String> q = queue(executor, 2, new ArrayList<>());
        assertEquals(GenerationQueue.State.WAITING, q.poll(1, true));
        assertEquals(GenerationQueue.State.WAITING, q.poll(2, true));
        assertTrue(q.saturated());
        assertEquals(GenerationQueue.State.WAITING, q.poll(3, true));
        assertEquals(2, executor.pending.size());
        executor.runAll();
        assertFalse(q.saturated());
        assertEquals(GenerationQueue.State.WAITING, q.poll(3, true));
        assertEquals(GenerationQueue.State.WAITING, q.poll(4, true));
        // Both slots are running again, so a fifth key is refused rather than queued.
        assertEquals(GenerationQueue.State.WAITING, q.poll(5, true));
        executor.runAll();
        // Four finished results with capacity two: the oldest unclaimed ones are dropped.
        assertEquals(2, q.retained());
        assertNull(q.take(1));
        assertNull(q.take(2));
        assertEquals("v3", q.take(3));
        assertEquals("v4", q.take(4));
        assertNull(q.take(5));
    }

    @Test public void failedWorkIsReadyWithoutAResultSoTheCallerGeneratesNow() {
        ManualExecutor executor = new ManualExecutor();
        List<Throwable> failures = new ArrayList<>();
        GenerationQueue<Integer, String> q = queue(executor, 4, failures);
        q.poll(-1, true);
        executor.runAll();
        assertEquals(1, failures.size());
        assertEquals(GenerationQueue.State.READY, q.poll(-1, true));
        assertNull(q.take(-1));
        assertEquals(0, q.running());
    }

    @Test public void rejectedSubmissionFallsBackToSynchronousGeneration() {
        GenerationQueue<Integer, String> q = queue(command -> {
            throw new RejectedExecutionException("shut down");
        }, 4, new ArrayList<>());
        assertEquals(GenerationQueue.State.WAITING, q.poll(3, true));
        assertEquals(GenerationQueue.State.READY, q.poll(3, true));
        assertNull(q.take(3));
        assertEquals(0, q.running());
    }

    @Test public void closingDropsResultsAndStopsScheduling() {
        ManualExecutor executor = new ManualExecutor();
        GenerationQueue<Integer, String> q = queue(executor, 4, new ArrayList<>());
        q.poll(1, true);
        q.close();
        executor.runAll();
        assertEquals(0, q.running());
        assertNull(q.take(1));
        assertEquals(GenerationQueue.State.READY, q.poll(2, true));
        assertTrue(executor.pending.isEmpty());
    }
}
