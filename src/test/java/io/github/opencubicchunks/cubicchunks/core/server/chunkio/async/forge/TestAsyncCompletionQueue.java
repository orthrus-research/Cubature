package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class TestAsyncCompletionQueue {
    @Test public void oldestReadyWorkProgressesInFifoOrderAcrossTicks() {
        AsyncCompletionQueue queue = new AsyncCompletionQueue(2, 100, () -> 0L);
        List<Integer> completed = new ArrayList<>();
        for (int i = 0; i < 5; i++) { int id = i; queue.offer(() -> completed.add(id)); }
        assertEquals(2, queue.drain()); assertEquals(0, queue.drain());
        assertEquals(Arrays.asList(0, 1), completed);
        queue.beginTick(); assertEquals(2, queue.drain());
        queue.beginTick(); assertEquals(1, queue.drain());
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), completed);
    }

    @Test public void startAndEndOpportunitiesShareOneCountAllowance() {
        AsyncCompletionQueue queue = new AsyncCompletionQueue(3, 100, () -> 0L);
        AtomicInteger completed = new AtomicInteger();
        queue.offer(completed::incrementAndGet); assertEquals(1, queue.drain());
        for (int i = 0; i < 3; i++) queue.offer(completed::incrementAndGet);
        assertEquals(2, queue.drain()); assertEquals(0, queue.drain());
        assertEquals(3, completed.get());
        queue.beginTick(); assertEquals(1, queue.drain());
        assertEquals(4, completed.get());
    }

    @Test public void elapsedTimeIsSharedAndIndivisibleOverrunDoesNotAdmitAnotherTask() {
        AtomicLong clock = new AtomicLong();
        AsyncCompletionQueue queue = new AsyncCompletionQueue(10, 10, clock::get);
        AtomicInteger completed = new AtomicInteger();
        for (int i = 0; i < 4; i++) queue.offer(() -> { completed.incrementAndGet(); clock.addAndGet(6); });
        assertEquals(2, queue.drain()); assertEquals(0, queue.drain());
        assertEquals(2, completed.get());
        queue.beginTick(); assertEquals(2, queue.drain());
        assertEquals(4, completed.get());
    }

    @Test public void oneLargeOldestCompletionCannotBeStarvedByItsCost() {
        AtomicLong clock = new AtomicLong();
        AsyncCompletionQueue queue = new AsyncCompletionQueue(2, 1, clock::get);
        AtomicInteger completed = new AtomicInteger();
        queue.offer(() -> { completed.incrementAndGet(); clock.addAndGet(100); });
        queue.offer(completed::incrementAndGet);
        assertEquals(1, queue.drain()); assertEquals(0, queue.drain());
        assertEquals(1, completed.get());
        queue.beginTick(); assertEquals(1, queue.drain()); assertEquals(2, completed.get());
    }

    @Test public void reentrantServiceCannotResetOrSpendAnotherAllowance() {
        AsyncCompletionQueue queue = new AsyncCompletionQueue(2, 100, () -> 0L);
        List<Integer> completed = new ArrayList<>();
        queue.offer(() -> {
            completed.add(1);
            assertEquals(0, queue.drain());
            queue.offer(() -> completed.add(3));
        });
        queue.offer(() -> completed.add(2));
        assertEquals(2, queue.drain()); assertEquals(Arrays.asList(1, 2), completed);
        queue.beginTick(); assertEquals(1, queue.drain()); assertEquals(Arrays.asList(1, 2, 3), completed);
    }

    @Test public void failureRemainsVisibleAndStillConsumesItsCountAndTime() {
        AtomicLong clock = new AtomicLong();
        AsyncCompletionQueue queue = new AsyncCompletionQueue(2, 10, clock::get);
        RuntimeException failure = new IllegalStateException("native completion failed");
        AtomicInteger next = new AtomicInteger();
        queue.offer(() -> { clock.set(12); throw failure; });
        queue.offer(next::incrementAndGet);
        try { queue.drain(); fail("Completion failure must remain visible"); }
        catch (RuntimeException expected) { assertSame(failure, expected); }
        assertEquals(0, queue.drain()); assertEquals(0, next.get());
        queue.beginTick(); assertEquals(1, queue.drain()); assertEquals(1, next.get());
    }

    @Test public void closedOldGenerationCannotPublishIntoReplacementQueue() {
        AsyncCompletionQueue old = new AsyncCompletionQueue(2, 100, () -> 0L);
        old.offer(() -> fail("Closed generation cannot publish")); old.close();
        assertFalse(old.offer(() -> fail("Late worker cannot publish"))); assertEquals(0, old.drain());
        AsyncCompletionQueue replacement = new AsyncCompletionQueue(2, 100, () -> 0L);
        AtomicInteger completed = new AtomicInteger(); replacement.offer(completed::incrementAndGet);
        assertEquals(1, replacement.drain()); assertEquals(1, completed.get());
    }

    @Test public void scopedDisposalKeepsOtherWorldsReadyWorkInOrder(){
        AsyncCompletionQueue queue=new AsyncCompletionQueue(8,100,()->0L);List<Integer> completed=new ArrayList<>();
        Runnable a=()->fail("Closing world cannot publish"),b=()->completed.add(1),c=()->completed.add(2);
        queue.offer(a);queue.offer(b);queue.offer(a);queue.offer(c);queue.discard(value->value==a);
        assertEquals(2,queue.drain());assertEquals(Arrays.asList(1,2),completed);
        assertTrue(queue.offer(()->completed.add(3)));assertEquals(1,queue.drain());assertEquals(Arrays.asList(1,2,3),completed);
    }

    @Test public void staleReadyTokenCannotCompleteOrRemoveReplacementRegistration() {
        AsyncCompletionQueue queue = new AsyncCompletionQueue(4, 100, () -> 0L);
        Map<String, Task> tasks = new ConcurrentHashMap<>();
        Task original = new Task(), replacement = new Task();
        AtomicInteger callbacks = new AtomicInteger();
        original.addCallback(value -> fail("Stale task cannot publish"));
        replacement.addCallback(value -> callbacks.incrementAndGet());
        tasks.put("cube", original);
        queue.offer(() -> AsyncTaskCompletion.completeIfCurrent(tasks, "cube", original));
        tasks.put("cube", replacement);
        queue.offer(() -> AsyncTaskCompletion.completeIfCurrent(tasks, "cube", replacement));
        assertEquals(2, queue.drain());
        assertEquals(0, original.completions); assertEquals(1, replacement.completions);
        assertEquals(1, callbacks.get()); assertTrue(tasks.isEmpty());
    }

    private static final class Task extends AsyncIOProvider<String> {
        int completions;
        Task() { finished = true; }
        @Override public void run() { finishAsync(); }
        @Override void runSynchronousPart() { ++completions; runCallbacks(); }
        @Override String get() { return "complete"; }
    }
}
