package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test public void queuedRetirementKeepsItsReservationAndRedemandStartsFreshOnlyAfterItDrains() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        GenerationQueue<Integer, String> q = new GenerationQueue<>(executor, key -> "v" + key + "#" + calls.incrementAndGet(),
                failure -> fail("no failure expected"), 1);
        q.poll(1, true);
        q.retire(1);
        q.retire(1);
        assertEquals(1, q.running());
        assertEquals(0, q.retained());
        assertTrue(q.saturated());
        assertEquals(GenerationQueue.State.WAITING, q.poll(1, true));
        assertEquals(GenerationQueue.State.WAITING, q.poll(2, true));
        assertEquals(1, executor.pending.size());
        executor.runAll();
        assertEquals(0, calls.get());
        assertEquals(0, q.running());
        assertEquals(0, q.retained());
        assertNull(q.take(1));
        q.poll(1, true);
        executor.runAll();
        assertEquals("v1#1", q.take(1));
        assertEquals(1, calls.get());
    }

    @Test public void completedValuesAndFailuresRetireWithoutLeavingMarkers() {
        ManualExecutor executor = new ManualExecutor();
        List<Throwable> failures = new ArrayList<>();
        GenerationQueue<Integer, String> q = queue(executor, 2, failures);
        q.poll(1, true);
        q.poll(-1, true);
        executor.runAll();
        assertEquals(2, q.retained());
        q.retire(1);
        q.retire(-1);
        q.retire(-1);
        assertEquals(0, q.running());
        assertEquals(0, q.retained());
        assertNull(q.take(1));
        assertNull(q.take(-1));
        assertEquals(1, failures.size());
    }

    @Test public void completedCallerWorkIsNotSettledAgainWhenTheSubmissionLaterRejects() throws InterruptedException {
        completedBeforeRejection(false);
    }

    @Test public void retiredCompletedCallerWorkCannotReappearWhenTheSubmissionLaterRejects() throws InterruptedException {
        completedBeforeRejection(true);
    }

    private static void completedBeforeRejection(boolean retire) throws InterruptedException {
        CountDownLatch submitted = new CountDownLatch(1), reject = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger submissions = new AtomicInteger(), calls = new AtomicInteger();
        ManualExecutor later = new ManualExecutor();
        GenerationQueue<Integer, String> q = new GenerationQueue<>(command -> {
            if (submissions.getAndIncrement() != 0) { later.execute(command); return; }
            submitted.countDown(); await(reject); throw new RejectedExecutionException("delayed rejection");
        }, key -> "v" + key + "#" + calls.incrementAndGet(), error -> fail("no work failure expected"), 1);
        Thread scheduler = start(() -> assertEquals(GenerationQueue.State.WAITING, q.poll(1, true)), failure);
        try {
            await(submitted);
            assertEquals(GenerationQueue.State.READY, q.poll(1, false));
            assertEquals(0, q.running());
            if (retire) { q.retire(1); assertEquals(GenerationQueue.State.WAITING, q.poll(1, true)); }
            reject.countDown();
        } finally { reject.countDown(); scheduler.join(10000); }
        assertFalse(scheduler.isAlive());
        assertNull(failure.get());
        assertEquals(retire ? 1 : 0, q.running());
        assertEquals(retire ? 0 : 1, q.retained());
        if (retire) { assertNull(q.take(1)); later.runAll(); }
        assertEquals(retire ? "v1#2" : "v1#1", q.take(1));
        assertEquals(retire ? 2 : 1, calls.get());
        assertEquals(0, q.running());
        assertEquals(0, q.retained());
    }

    @Test public void claimedCallerWorkKeepsItsCapacityWhenTheSubmissionRejects() throws InterruptedException {
        claimedDuringRejection(false);
    }

    @Test public void retiredClaimedCallerWorkKeepsItsCapacityUntilTheOwnerFinishes() throws InterruptedException {
        claimedDuringRejection(true);
    }

    private static void claimedDuringRejection(boolean retire) throws InterruptedException {
        CountDownLatch submitted = new CountDownLatch(1), reject = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Future<?>> submittedFuture = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean(), completed = new AtomicBoolean();
        GenerationQueue<Integer, String> q = new GenerationQueue<>(command -> {
            submittedFuture.set((Future<?>) command);
            submitted.countDown(); await(reject); throw new RejectedExecutionException("delayed rejection");
        }, key -> {
            calls.incrementAndGet(); started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("work was not released");
            } catch (InterruptedException e) {
                interrupted.set(true); Thread.currentThread().interrupt(); throw new IllegalStateException(e);
            }
            completed.set(true);
            return "v" + key;
        }, error -> fail("no work failure expected"), 1);
        Thread scheduler = start(() -> assertEquals(GenerationQueue.State.WAITING, q.poll(1, true)), failure);
        Thread caller = null;
        try {
            await(submitted);
            caller = start(() -> assertEquals(GenerationQueue.State.READY, q.poll(1, false)), failure);
            await(started);
            if (retire) q.retire(1);
            reject.countDown();
            scheduler.join(10000);
            assertFalse(scheduler.isAlive());
            assertFalse(submittedFuture.get().isCancelled());
            assertFalse(submittedFuture.get().isDone());
            assertEquals(1, q.running());
            assertEquals(0, q.retained());
            assertTrue(q.saturated());
            assertEquals(GenerationQueue.State.WAITING, q.poll(1, true));
            assertEquals(GenerationQueue.State.WAITING, q.poll(2, true));
            assertNull(q.take(1));
        } finally {
            reject.countDown(); release.countDown(); scheduler.join(10000);
            if (caller != null) caller.join(10000);
        }
        assertFalse(caller == null || caller.isAlive());
        assertNull(failure.get());
        assertFalse(interrupted.get());
        assertTrue(completed.get());
        assertEquals(1, calls.get());
        assertEquals(0, q.running());
        assertEquals(retire ? 0 : 1, q.retained());
        assertEquals(retire ? null : "v1", q.take(1));
    }

    @Test public void rejectedUnclaimedSubmissionCannotStartItsDispatchedFutureLater() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GenerationQueue<Integer, String> q = new GenerationQueue<>(command -> {
            worker.set(start(command, failure));
            awaitBlockedClaim(worker.get());
            throw new RejectedExecutionException("dispatched before rejection");
        }, key -> { calls.incrementAndGet(); return "v" + key; }, error -> fail("no failure expected"), 1);
        // Keep the dispatched callback out of claim until rejection has settled its token.
        try {
            synchronized (q) { assertEquals(GenerationQueue.State.WAITING, q.poll(1, true)); }
        } finally {
            if (worker.get() != null) worker.get().join(10000);
        }
        assertFalse(worker.get().isAlive());
        assertNull(failure.get());
        assertEquals(0, calls.get());
        assertEquals(0, q.running());
        assertNull(q.take(1));
        assertEquals(0, q.retained());
    }

    @Test public void closingRetiredQueuedWorkDoesNotRunOrRepublishIt() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        GenerationQueue<Integer, String> q = new GenerationQueue<>(executor, key -> { calls.incrementAndGet(); return "v" + key; },
                error -> fail("no failure expected"), 1);
        q.poll(1, true); q.retire(1); q.close(); executor.runAll();
        assertEquals(0, calls.get());
        assertEquals(0, q.running());
        assertEquals(0, q.retained());
        assertNull(q.take(1));
    }

    private static Thread start(Runnable action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try { action.run(); } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "generation-queue-regression");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static void awaitBlockedClaim(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId(), 16);
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == Thread.currentThread().threadId()
                    && info.getLockInfo() != null && info.getLockInfo().getClassName().equals(GenerationQueue.class.getName())) {
                for (StackTraceElement frame : info.getStackTrace())
                    if (frame.getClassName().equals(GenerationQueue.class.getName()) && frame.getMethodName().equals("run")) return;
            }
            Thread.yield();
        }
        throw new AssertionError("dispatched work did not block before queue claim");
    }
}
