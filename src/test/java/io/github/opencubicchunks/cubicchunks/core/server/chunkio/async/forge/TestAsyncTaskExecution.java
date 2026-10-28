package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Controlled workers and latches test ownership rather than relying on scheduling sleeps. */
public class TestAsyncTaskExecution {
    private static final class ManualExecutor extends ThreadPoolExecutor {
        boolean reject;
        ManualExecutor() { super(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("injected rejection");
            getQueue().add(task);
        }
        void runNext() {
            Runnable next = getQueue().poll();
            assertNotNull("Expected one admitted task", next);
            next.run();
        }
    }

    private static AsyncTaskExecution execution(ManualExecutor executor, Runnable load) {
        return new AsyncTaskExecution(executor, load, error -> { throw error; });
    }

    @Test public void unresolvedParentDoesNotOccupyWorkerOrBlockReadyUnrelatedWork() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger unresolvedLoads = new AtomicInteger(), readyLoads = new AtomicInteger();
        AsyncTaskExecution unresolved = execution(executor, unresolvedLoads::incrementAndGet);
        AsyncTaskExecution ready = execution(executor, readyLoads::incrementAndGet);
        ready.enqueue();
        executor.runNext();
        assertEquals(1, readyLoads.get());
        assertEquals(0, unresolvedLoads.get());
        assertTrue(executor.getQueue().isEmpty());
        unresolved.enqueue();
        executor.runNext();
        assertEquals(1, unresolvedLoads.get());
    }

    @Test public void synchronousStealBeforeDependencyAdmissionRunsExactlyOnce() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncTaskExecution task = execution(executor, loads::incrementAndGet);
        task.runOrAwait();
        task.enqueue();
        task.runOrAwait();
        task.run();
        assertEquals(1, loads.get());
        assertTrue(executor.getQueue().isEmpty());
    }

    @Test public void recursiveOwnerDecodeFailsVisiblyInsteadOfWaitingOnItself() {
        ManualExecutor executor = new ManualExecutor(); AtomicInteger loads = new AtomicInteger();
        AsyncTaskExecution[] holder = new AsyncTaskExecution[1];
        holder[0] = execution(executor, () -> {
            loads.incrementAndGet();
            try { holder[0].runOrAwait(); fail("Recursive decode must not wait on its own thread"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("own execution")); }
        });
        holder[0].runOrAwait(); holder[0].runOrAwait();
        assertEquals(1, loads.get()); assertTrue(executor.getQueue().isEmpty());
    }

    @Test public void synchronousStealWinsEvenIfWorkerAlreadyDequeuedItsWrapper() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncTaskExecution task = execution(executor, loads::incrementAndGet);
        task.enqueue();
        Runnable dequeued = executor.getQueue().remove();
        task.runOrAwait();
        dequeued.run();
        task.enqueue();
        assertEquals(1, loads.get());
    }

    @Test public void repeatedDependencyNotificationEnqueuesOneWrapper() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        AsyncTaskExecution task = execution(executor, loads::incrementAndGet);
        task.enqueue(); task.enqueue(); task.enqueue();
        assertEquals(1, executor.getQueue().size());
        executor.runNext();
        task.enqueue();
        assertEquals(1, loads.get());
        assertTrue(executor.getQueue().isEmpty());
    }

    @Test public void cancellationBeforeParentArrivalPreventsLaterAdmission() {
        ManualExecutor executor = new ManualExecutor();
        AsyncTaskExecution task = execution(executor, () -> fail("Cancelled dependency must not decode"));
        assertTrue(task.cancelBeforeStart());
        assertFalse(task.cancelBeforeStart());
        task.enqueue(); task.run();
        assertTrue(executor.getQueue().isEmpty());
        try { task.runOrAwait(); fail("Cancelled task cannot be synchronously stolen"); }
        catch (CancellationException expected) { }
    }

    @Test public void queuedCancellationWinsAfterDequeueButBeforeWorkerClaim() {
        ManualExecutor executor = new ManualExecutor();
        AsyncTaskExecution task = execution(executor, () -> fail("Cancelled task must not decode"));
        task.enqueue();
        Runnable dequeued = executor.getQueue().remove();
        assertTrue(task.cancelBeforeStart());
        dequeued.run();
    }

    @Test public void workerClaimPreventsStealOrCancellationFromExecutingAnotherDecode() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), ownerDone = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AsyncTaskExecution task = execution(executor, () -> {
            loads.incrementAndGet(); entered.countDown(); await(release);
        });
        task.enqueue();
        Thread worker = thread(() -> executor.runNext(), failure);
        Thread owner = thread(() -> { try { task.runOrAwait(); } finally { ownerDone.countDown(); } }, failure);
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(task.cancelBeforeStart());
            owner.start();
            release.countDown();
            assertTrue(ownerDone.await(5, TimeUnit.SECONDS));
            worker.join(5000); owner.join(5000);
            assertFalse(worker.isAlive()); assertFalse(owner.isAlive());
            assertNull(failure.get()); assertEquals(1, loads.get());
        } finally {
            release.countDown(); worker.interrupt(); owner.interrupt();
            worker.join(1000); owner.join(1000);
        }
    }

    @Test public void interruptedSynchronousWaitPreservesInterruptAndRunningOwnership() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), ownerStarted = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AsyncTaskExecution task = execution(executor, () -> { entered.countDown(); await(release); });
        task.enqueue();
        Thread worker = thread(executor::runNext, failure);
        Thread owner = thread(() -> {
            ownerStarted.countDown();
            try {
                task.runOrAwait();
                fail("Interrupted wait must report failure");
            } catch (RuntimeException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(expected.getCause() instanceof InterruptedException);
            } finally { done.countDown(); }
        }, failure);
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            owner.start(); assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));
            owner.interrupt();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertFalse(task.cancelBeforeStart());
            release.countDown(); worker.join(5000); owner.join(5000);
            assertNull(failure.get());
        } finally {
            release.countDown(); worker.interrupt(); owner.interrupt();
            worker.join(1000); owner.join(1000);
        }
    }

    @Test public void rejectionIsTerminalAndReportedOnceWithoutRunningOnSubmitter() {
        ManualExecutor executor = new ManualExecutor(); executor.reject = true;
        AtomicInteger rejected = new AtomicInteger();
        AsyncTaskExecution task = new AsyncTaskExecution(executor, () -> fail("Rejected decode must not run inline"), error -> rejected.incrementAndGet());
        task.enqueue(); task.enqueue(); task.runOrAwait();
        assertEquals(1, rejected.get());
        assertFalse(task.cancelBeforeStart());
    }

    @Test public void missingParentCompletesAbsentCubeWithoutReadingStorage() {
        ManualExecutor executor = new ManualExecutor();
        AsyncCubeIOProvider task = new AsyncCubeIOProvider(new QueuedCube(2, -3, 4, null), null);
        AtomicInteger ready = new AtomicInteger(), callbacks = new AtomicInteger();
        task.prepareExecution(executor, ready::incrementAndGet);
        task.addCallback(cube -> { assertNull(cube); callbacks.incrementAndGet(); });
        task.setColumn(null); task.setColumn(null);
        executor.runNext();
        assertEquals(1, ready.get()); assertTrue(task.isFinished());
        AsyncTaskCompletion.complete(task);
        assertEquals(1, callbacks.get()); assertNull(task.get());
    }

    @Test public void failedParentReachesVisibleTerminalCubeFailureWithoutStorageRead() {
        ManualExecutor executor = new ManualExecutor();
        AsyncCubeIOProvider task = new AsyncCubeIOProvider(new QueuedCube(2, -3, 4, null), null);
        RuntimeException parentFailure = new IllegalStateException("parent load failed");
        AtomicInteger ready = new AtomicInteger(), cleanup = new AtomicInteger();
        task.prepareExecution(executor, ready::incrementAndGet);
        task.setCleanup(cleanup::incrementAndGet);
        task.failDependency(parentFailure);
        executor.runNext();
        assertEquals(1, ready.get()); assertTrue(task.isFinished());
        try { AsyncTaskCompletion.complete(task); fail("Parent failure must remain visible"); }
        catch (RuntimeException expected) { assertSame(parentFailure, expected.getCause().getCause()); }
        assertEquals(1, cleanup.get());
    }

    @Test public void dependencyWaitingCancellationReleasesOnlyItsOwnRegistrationAndPin() {
        ManualExecutor executor = new ManualExecutor();
        AsyncCubeIOProvider task = new AsyncCubeIOProvider(new QueuedCube(1, 2, 3, null), null);
        Map<String, AsyncCubeIOProvider> tasks = new ConcurrentHashMap<>();
        AtomicInteger cleanup = new AtomicInteger();
        Consumer<io.github.opencubicchunks.cubicchunks.core.world.cube.Cube> callback = value -> fail("Cancelled callback");
        task.prepareExecution(executor, () -> fail("Cancelled dependency cannot become ready"));
        task.setCleanup(cleanup::incrementAndGet); task.addCallback(callback); tasks.put("cube", task);
        task.removeCallback(callback);
        AsyncTaskCompletion.cancelIfUnneeded(tasks, "cube", task, executor);
        assertTrue(tasks.isEmpty()); assertEquals(1, cleanup.get());
        task.setColumn(null);
        assertTrue(executor.getQueue().isEmpty());
        task.cleanup(); assertEquals(1, cleanup.get());
    }

    @Test public void columnAdmissionFailureNotifiesDependentsAndRemainsVisible() {
        ManualExecutor executor = new ManualExecutor(); executor.reject = true;
        AtomicReference<Throwable> dependencyFailure = new AtomicReference<>();
        AsyncColumnIOProvider task = new AsyncColumnIOProvider(new QueuedColumn(1, 2, null), null, null,
                column -> fail("Rejected column cannot publish"), dependencyFailure::set);
        AtomicInteger ready = new AtomicInteger(), cleanup = new AtomicInteger();
        task.prepareExecution(executor, ready::incrementAndGet); task.setCleanup(cleanup::incrementAndGet);
        task.enqueue();
        assertEquals(1, ready.get()); assertTrue(task.isFinished());
        try { AsyncTaskCompletion.complete(task); fail("Admission failure must remain visible"); }
        catch (RejectedExecutionException expected) { assertSame(expected, dependencyFailure.get()); }
        assertEquals(1, cleanup.get());
    }

    @Test public void lateParentAfterShutdownCancellationCannotRestartDecoder() {
        ManualExecutor executor = new ManualExecutor();
        AsyncCubeIOProvider task = new AsyncCubeIOProvider(new QueuedCube(1, 2, 3, null), null);
        AtomicInteger cleanup = new AtomicInteger();
        task.prepareExecution(executor, () -> fail("Stopped dependency cannot publish")); task.setCleanup(cleanup::incrementAndGet);
        assertTrue(task.cancelBeforeStart(executor));
        AsyncTaskCompletion.cleanupStoppedTasks(java.util.Collections.singletonList(task), java.util.Collections.singletonList(task));
        task.setColumn(null); task.failDependency(new IllegalStateException("stale parent failure"));
        assertTrue(executor.getQueue().isEmpty()); assertEquals(1, cleanup.get());
    }

    private static Thread thread(Runnable action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable error) { failure.compareAndSet(null, error); } }, "CC deterministic execution test");
        thread.setDaemon(true);
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
