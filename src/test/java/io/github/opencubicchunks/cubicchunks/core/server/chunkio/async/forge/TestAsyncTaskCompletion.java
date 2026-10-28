/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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
package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Completion ownership tests require no Minecraft world or I/O threads. */
public class TestAsyncTaskCompletion {
    private static class Task extends AsyncIOProvider<String> {
        int completions;

        Task(boolean finished) {
            this.finished = finished;
        }

        @Override public void run() {
            finished = true;
        }

        @Override void runSynchronousPart() {
            completions++;
            runCallbacks();
        }

        @Override String get() {
            return "loaded";
        }
    }

    @Test public void directCompletionIsClaimedOnceIncludingReentrantNativePublication() {
        AtomicInteger cleanup = new AtomicInteger(), callbacks = new AtomicInteger();
        Task task = new Task(true) {
            @Override void runSynchronousPart() {
                assertTrue(isCompleting());
                AsyncTaskCompletion.complete(this);
                super.runSynchronousPart();
            }
        };
        task.addCallback(value -> callbacks.incrementAndGet()); task.setCleanup(cleanup::incrementAndGet);
        AsyncTaskCompletion.complete(task); AsyncTaskCompletion.complete(task);
        assertEquals(1, task.completions); assertEquals(1, callbacks.get()); assertEquals(1, cleanup.get());
    }

    @Test public void asynchronousNativePublicationKeepsSyncAndAsyncReentryOnItsOriginalTask() {
        assertPublicationReentry(true);
    }

    @Test public void synchronousNativePublicationKeepsSyncAndAsyncReentryOnItsOriginalTask() {
        assertPublicationReentry(false);
    }

    private void assertPublicationReentry(boolean queued) {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        AtomicInteger original = new AtomicInteger(), joined = new AtomicInteger(), cleanup = new AtomicInteger();
        Task task = new Task(true) {
            @Override void runSynchronousPart() {
                assertSame(this, tasks.get("cube")); assertTrue(isCompleting());
                tasks.get("cube").addCallback(value -> joined.incrementAndGet());
                AsyncTaskCompletion.completeForSynchronousLoad(tasks, "cube", this);
                AsyncTaskCompletion.drain(tasks);
                assertSame(this, tasks.get("cube"));
                assertEquals(0, original.get()); assertEquals(0, joined.get()); assertEquals(0, cleanup.get());
                super.runSynchronousPart();
            }
        };
        task.addCallback(value -> original.incrementAndGet()); task.setCleanup(cleanup::incrementAndGet); tasks.put("cube", task);
        if (queued) AsyncTaskCompletion.drain(tasks);
        else AsyncTaskCompletion.completeForSynchronousLoad(tasks, "cube", task);
        assertTrue(tasks.isEmpty()); assertEquals(1, task.completions); assertEquals(1, original.get());
        assertEquals(1, joined.get()); assertEquals(1, cleanup.get());
    }

    @Test public void synchronousNativeLoadWithoutCallbacksStillPublishesAndReleasesOwnership() {
        Map<String, Task> tasks = new ConcurrentHashMap<>(); Task task = new Task(true);
        AtomicInteger cleanup = new AtomicInteger(); task.setCleanup(cleanup::incrementAndGet); tasks.put("cube", task);
        AsyncTaskCompletion.completeForSynchronousLoad(tasks, "cube", task);
        assertEquals(1, task.completions); assertEquals(1, cleanup.get()); assertTrue(tasks.isEmpty());
    }

    @Test public void failingSynchronousPublicationDoesNotRemoveAReentrantReplacement() {
        Map<String, Task> tasks = new ConcurrentHashMap<>(); Task replacement = new Task(false);
        RuntimeException failure = new IllegalStateException("native publication failed");
        AtomicInteger cleanup = new AtomicInteger();
        Task task = new Task(true) {
            @Override void runSynchronousPart() { tasks.put("cube", replacement); throw failure; }
        };
        task.setCleanup(cleanup::incrementAndGet); tasks.put("cube", task);
        try { AsyncTaskCompletion.completeForSynchronousLoad(tasks, "cube", task); fail("Failure must remain visible"); }
        catch (RuntimeException expected) { assertSame(failure, expected); }
        assertSame(replacement, tasks.get("cube")); assertEquals(1, cleanup.get());
    }

    @Test public void asynchronousPublicationWakesTheWaitingOwner() throws Exception {
        Task task = new Task(false);
        CountDownLatch waiting = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger observed = new AtomicInteger();
        int[] loadedData = {0};
        Thread waiter = new Thread(() -> {
            try {
                synchronized (task) {
                    waiting.countDown();
                    while (!task.isFinished()) task.wait();
                    observed.set(loadedData[0]);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { done.countDown(); }
        }, "async-load-publication-test");
        waiter.setDaemon(true);
        waiter.start();
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            loadedData[0] = 42;
            task.finishAsync();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(task.isFinished());
            assertEquals(42, observed.get());
        } finally {
            waiter.interrupt();
            waiter.join(1000);
        }
    }

    @Test public void reentrantDrainCannotCompleteTheSameTaskTwice() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task task = new Task(true);
        AtomicInteger callbacks = new AtomicInteger();
        task.addCallback(value -> {
            assertEquals("loaded", value);
            callbacks.incrementAndGet();
            AsyncTaskCompletion.drain(tasks);
        });
        tasks.put("column", task);
        AsyncTaskCompletion.drain(tasks);
        assertEquals(1, task.completions);
        assertEquals(1, callbacks.get());
        assertTrue(tasks.isEmpty());
    }

    @Test public void replacementQueuedByCallbackKeepsItsOwnership() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task original = new Task(true);
        Task replacement = new Task(false);
        replacement.addCallback(value -> { });
        original.addCallback(value -> tasks.put("column", replacement));
        tasks.put("column", original);
        AsyncTaskCompletion.drain(tasks);
        assertSame(replacement, tasks.get("column"));
        assertEquals(1, original.completions);
        assertEquals(0, replacement.completions);
        replacement.run();
        AsyncTaskCompletion.drain(tasks);
        assertEquals(1, replacement.completions);
        assertTrue(tasks.isEmpty());
    }

    @Test public void unfinishedTaskRemainsQueued() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task task = new Task(false);
        tasks.put("cube", task);
        AsyncTaskCompletion.drain(tasks);
        assertSame(task, tasks.get("cube"));
        assertEquals(0, task.completions);
    }

    @Test public void sameCoordinateRequestDuringCompletionJoinsExistingTask() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task task = new Task(true);
        AtomicInteger joined = new AtomicInteger();
        task.addCallback(value -> {
            Task existing = tasks.get("cube");
            assertSame(task, existing);
            existing.addCallback(next -> joined.incrementAndGet());
            AsyncTaskCompletion.drain(tasks);
        });
        tasks.put("cube", task);
        AsyncTaskCompletion.drain(tasks);
        assertEquals(1, task.completions);
        assertEquals(1, joined.get());
        assertTrue(tasks.isEmpty());
    }

    @Test public void fatalErrorEscapesImmediatelyAndReleasesQueuedOwnership() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task task = new Task(true);
        Error fatal = new AssertionError("fatal callback");
        AtomicInteger later = new AtomicInteger(), cleanup = new AtomicInteger();
        task.setCleanup(cleanup::incrementAndGet);
        task.addCallback(value -> { throw fatal; });
        task.addCallback(value -> later.incrementAndGet());
        tasks.put("cube", task);
        try {
            AsyncTaskCompletion.drain(tasks);
            fail("Fatal errors must not be treated as recoverable consumer failures");
        } catch (Error error) {
            assertSame(fatal, error);
        }
        assertEquals(0, later.get());
        assertEquals(1, cleanup.get());
        assertTrue(tasks.isEmpty());
    }

    @Test public void failedCallbackStillNotifiesOtherConsumersAndReleasesTask() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task task = new Task(true);
        RuntimeException failure = new IllegalStateException("consumer failure");
        AtomicInteger failures = new AtomicInteger(), cleanup = new AtomicInteger(), released = new AtomicInteger();
        task.setCleanup(released::incrementAndGet);
        task.addCallback(value -> { failures.incrementAndGet(); throw failure; });
        task.addCallback(value -> cleanup.incrementAndGet());
        tasks.put("cube", task);
        try {
            AsyncTaskCompletion.drain(tasks);
            fail("Callback failure must remain visible");
        } catch (RuntimeException error) {
            assertSame(failure, error);
        }
        assertTrue(tasks.isEmpty());
        assertEquals(1, cleanup.get());
        assertEquals(1, released.get());
        task.runCallbacks();
        AsyncTaskCompletion.drain(tasks);
        assertEquals(1, failures.get());
        assertEquals(1, task.completions);
    }

    @Test public void callbackFailuresPreserveSuppressedCauses() {
        Task task = new Task(true);
        RuntimeException first = new IllegalStateException("first");
        RuntimeException second = new IllegalArgumentException("second");
        task.addCallback(value -> { throw first; });
        task.addCallback(value -> { throw second; });
        task.addCallback(value -> { throw first; });
        try {
            task.runCallbacks();
            fail("Callback failure must remain visible");
        } catch (RuntimeException error) {
            assertSame(first, error);
            assertArrayEquals(new Throwable[] {second}, error.getSuppressed());
        }
        assertFalse(task.hasCallbacks());
    }

    @Test public void reentrantCallbackDrainClaimsEachConsumerOnce() {
        Task task = new Task(true);
        List<Integer> calls = new ArrayList<>();
        task.addCallback(value -> { calls.add(1); task.runCallbacks(); });
        task.addCallback(value -> calls.add(2));
        task.runCallbacks();
        assertEquals(Arrays.asList(1, 2), calls);
        assertFalse(task.hasCallbacks());
    }

    @Test public void callbackAddedDuringDispatchIsDeliveredAndRemovedCallbackIsSkipped() {
        Task task = new Task(true);
        List<Integer> calls = new ArrayList<>();
        Consumer<String> removed = value -> calls.add(99);
        task.addCallback(value -> { calls.add(1); task.addCallback(next -> calls.add(3)); });
        task.addCallback(removed);
        task.addCallback(value -> calls.add(2));
        task.removeCallback(removed);
        task.runCallbacks();
        assertEquals(Arrays.asList(1, 2, 3), calls);
        assertFalse(task.hasCallbacks());
    }

    @Test public void replacementSurvivesAnEarlierConsumerFailure() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task original = new Task(true);
        Task replacement = new Task(false);
        RuntimeException failure = new IllegalStateException("consumer failure");
        original.addCallback(value -> { throw failure; });
        original.addCallback(value -> tasks.put("cube", replacement));
        tasks.put("cube", original);
        try {
            AsyncTaskCompletion.drain(tasks);
            fail("Callback failure must remain visible");
        } catch (RuntimeException error) {
            assertSame(failure, error);
        }
        assertSame(replacement, tasks.get("cube"));
    }

    @Test public void nativeCompletionFailureBeforeCallbacksStillReleasesResources() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        RuntimeException failure = new IllegalStateException("native loading failure");
        Task task = new Task(true) {
            @Override void runSynchronousPart() {
                throw failure;
            }
        };
        AtomicInteger callbacks = new AtomicInteger(), cleanup = new AtomicInteger();
        task.addCallback(value -> callbacks.incrementAndGet());
        task.setCleanup(cleanup::incrementAndGet);
        tasks.put("cube", task);
        try {
            AsyncTaskCompletion.drain(tasks);
            fail("Native loading failures must remain visible");
        } catch (RuntimeException error) {
            assertSame(failure, error);
        }
        assertEquals(0, callbacks.get());
        assertEquals(1, cleanup.get());
        assertTrue(tasks.isEmpty());
    }

    @Test public void cleanupDoesNotCountAsConsumerAndRunsAfterEveryConsumer() {
        Task task = new Task(true);
        List<String> calls = new ArrayList<>();
        task.setCleanup(() -> calls.add("release"));
        assertFalse(task.hasCallbacks());
        task.addCallback(value -> {
            calls.add("first");
            task.addCallback(next -> calls.add("joined"));
        });
        task.addCallback(value -> calls.add("second"));
        AsyncTaskCompletion.complete(task);
        task.cleanup();
        assertEquals(Arrays.asList("first", "second", "joined", "release"), calls);
    }

    @Test public void oldCompletionReleasesOnlyItsOwnColumnPin() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Set<Task> pins = new HashSet<>();
        Task original = new Task(true), replacement = new Task(false);
        pins.add(original);
        original.setCleanup(() -> pins.remove(original));
        replacement.setCleanup(() -> pins.remove(replacement));
        original.addCallback(value -> {
            tasks.put("cube", replacement);
            pins.add(replacement);
        });
        tasks.put("cube", original);
        AsyncTaskCompletion.drain(tasks);
        assertEquals(1, pins.size());
        assertTrue(pins.contains(replacement));
        assertSame(replacement, tasks.get("cube"));
        replacement.run();
        AsyncTaskCompletion.drain(tasks);
        assertTrue(pins.isEmpty());
    }

    @Test public void droppingLastConsumerCancelsQueuedReadAndReleasesResources() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task task = new Task(false);
        AtomicInteger cleanup = new AtomicInteger();
        Consumer<String> consumer = value -> fail("Canceled read must not deliver a result");
        task.addCallback(AsyncIOProvider.withCallbackIdentity(consumer, value -> {
            fail("Canceled read must not run provider postprocessing");
            consumer.accept(value);
        }));
        task.setCleanup(cleanup::incrementAndGet);
        tasks.put("cube", task);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            // Queue directly so no worker can start the task before cancellation.
            executor.getQueue().add(task);
            AsyncTaskCompletion.cancelIfUnneeded(tasks, "cube", task, executor);
            assertSame(task, tasks.get("cube"));
            assertEquals(0, cleanup.get());
            task.removeCallback(consumer);
            AsyncTaskCompletion.cancelIfUnneeded(tasks, "cube", task, executor);
            assertTrue(tasks.isEmpty());
            assertTrue(executor.getQueue().isEmpty());
            assertEquals(1, cleanup.get());
            assertFalse(task.isFinished());
            assertEquals(0, task.completions);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test public void droppingLastConsumerDuringReadKeepsResourcesThenDiscardsWithoutNativeSideEffects() throws InterruptedException {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1), readFinished = new CountDownLatch(1);
        Task task = new Task(false) {
            @Override void runSynchronousPart() {
                throw new AssertionError("Canceled read must not publish entities, scheduled ticks or cached cubes");
            }

            @Override public void run() {
                started.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Test did not release read worker");
                    }
                    super.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    readFinished.countDown();
                }
            }
        };
        AtomicInteger cleanup = new AtomicInteger();
        Thread serverThread = Thread.currentThread();
        task.setCleanup(() -> {
            assertSame(serverThread, Thread.currentThread());
            cleanup.incrementAndGet();
        });
        Consumer<String> consumer = value -> fail("Dropped consumer must not receive a result");
        task.addCallback(consumer);
        tasks.put("cube", task);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            executor.execute(task);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            task.removeCallback(consumer);
            AsyncTaskCompletion.cancelIfUnneeded(tasks, "cube", task, executor);
            assertSame(task, tasks.get("cube"));
            assertEquals(0, cleanup.get());
            AsyncTaskCompletion.drain(tasks);
            assertEquals(0, task.completions);
            finish.countDown();
            assertTrue(readFinished.await(5, TimeUnit.SECONDS));
            assertEquals(0, cleanup.get());
            AsyncTaskCompletion.drain(tasks);
            assertEquals(0, task.completions);
            assertEquals(1, cleanup.get());
            assertTrue(tasks.isEmpty());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void explicitSynchronousLoadCompletesEvenWithoutConsumers() {
        Task task = new Task(true);
        AtomicInteger cleanup = new AtomicInteger();
        task.setCleanup(cleanup::incrementAndGet);
        assertFalse(task.hasCallbacks());
        AsyncTaskCompletion.complete(task);
        assertEquals(1, task.completions);
        assertEquals(1, cleanup.get());
    }

    @Test public void canceledReadRejoinedBeforeDispatchStillCompletesNormally() {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task task = new Task(true);
        Consumer<String> abandoned = value -> fail("Canceled consumer must stay canceled");
        AtomicInteger joined = new AtomicInteger(), cleanup = new AtomicInteger();
        task.addCallback(abandoned);
        task.setCleanup(cleanup::incrementAndGet);
        tasks.put("cube", task);
        task.removeCallback(abandoned);
        task.addCallback(value -> joined.incrementAndGet());
        AsyncTaskCompletion.drain(tasks);
        assertEquals(1, task.completions);
        assertEquals(1, joined.get());
        assertEquals(1, cleanup.get());
        assertTrue(tasks.isEmpty());
    }

    @Test public void reentrantDropDuringNativePublicationKeepsProviderRegistration() {
        assertReentrantDropKeepsProviderRegistration(true);
    }

    @Test public void explicitSyncCompletionAlsoLocksCancellationDuringPublication() {
        assertReentrantDropKeepsProviderRegistration(false);
    }

    private void assertReentrantDropKeepsProviderRegistration(boolean queued) {
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        AtomicBoolean invalidWatcher = new AtomicBoolean();
        AtomicInteger registration = new AtomicInteger(), watcher = new AtomicInteger(), cleanup = new AtomicInteger();
        Consumer<String> original = value -> {
            if (!invalidWatcher.get()) watcher.incrementAndGet();
        };
        Task task = new Task(true) {
            @Override void runSynchronousPart() {
                // Model a native load event invalidating the watcher before provider callbacks run.
                invalidWatcher.set(true);
                removeCallback(original);
                super.runSynchronousPart();
            }
        };
        task.addCallback(AsyncIOProvider.withCallbackIdentity(original, value -> {
            registration.incrementAndGet();
            original.accept(value);
        }));
        task.setCleanup(cleanup::incrementAndGet);
        if (queued) {
            tasks.put("cube", task);
            AsyncTaskCompletion.drain(tasks);
            assertTrue(tasks.isEmpty());
        } else {
            AsyncTaskCompletion.complete(task);
        }
        assertEquals(1, registration.get());
        assertEquals(0, watcher.get());
        assertEquals(1, task.completions);
        assertEquals(1, cleanup.get());
    }

    @Test public void shutdownOnlyReleasesFinishedOrNeverStartedTaskOwnership() {
        Task finished = new Task(true), neverStarted = new Task(false), running = new Task(false);
        List<Task> tasks = Arrays.asList(finished, neverStarted, running);
        AtomicInteger released = new AtomicInteger();
        Set<Task> pins = new HashSet<>(tasks);
        for (Task task : tasks) task.setCleanup(() -> {
            assertTrue(pins.remove(task));
            released.incrementAndGet();
        });
        AsyncTaskCompletion.cleanupStoppedTasks(tasks, Collections.singletonList(neverStarted));
        assertEquals(Collections.singleton(running), pins);
        assertEquals(2, released.get());
        assertEquals(0, finished.completions);
        assertEquals(0, neverStarted.completions);
        assertEquals(0, running.completions);
        running.run();
        AsyncTaskCompletion.cleanupStoppedTasks(tasks, Collections.emptyList());
        assertTrue(pins.isEmpty());
        assertEquals(3, released.get());
    }

    @Test public void originalRunnableCancelsNestedProviderWrappers() {
        Task task = new Task(true);
        List<String> calls = new ArrayList<>();
        Runnable original = () -> calls.add("vanilla runnable");
        Consumer<String> adapter = AsyncIOProvider.withCallbackIdentity(original, value -> original.run());
        Consumer<String> postprocessor = AsyncIOProvider.withCallbackIdentity(adapter, value -> {
            calls.add("provider postprocessing");
            adapter.accept(value);
        });
        task.addCallback(postprocessor);
        task.addCallback(value -> calls.add("other request"));
        task.removeCallback(original);
        task.runCallbacks();
        assertEquals(Arrays.asList("other request"), calls);
    }

    @Test public void cancellationRemovesOnlyOneSubscriptionWithTheSameIdentity() {
        Task task = new Task(true);
        List<String> calls = new ArrayList<>();
        Consumer<String> original = value -> calls.add(value);
        task.addCallback(AsyncIOProvider.withCallbackIdentity(original, value -> original.accept("first")));
        task.addCallback(AsyncIOProvider.withCallbackIdentity(original, value -> original.accept("second")));
        task.removeCallback(original);
        task.runCallbacks();
        assertEquals(Arrays.asList("second"), calls);
    }
}
