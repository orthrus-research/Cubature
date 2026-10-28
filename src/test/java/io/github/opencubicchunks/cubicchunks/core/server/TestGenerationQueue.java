package io.github.opencubicchunks.cubicchunks.core.server;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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

    @Test public void outsideTheScheduledScopeAnUnknownKeyIsReadyAndNothingIsSubmitted() {
        ManualExecutor executor = new ManualExecutor();
        GenerationQueue<Integer, String> q = queue(executor, 4, new ArrayList<>());
        assertEquals(GenerationQueue.State.READY, q.poll(1, false));
        assertTrue(executor.pending.isEmpty());
        assertNull(q.take(1));
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
