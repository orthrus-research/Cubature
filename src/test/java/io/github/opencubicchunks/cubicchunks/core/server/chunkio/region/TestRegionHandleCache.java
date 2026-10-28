package io.github.opencubicchunks.cubicchunks.core.server.chunkio.region;

import cubicchunks.regionlib.api.region.IRegion;
import cubicchunks.regionlib.api.region.key.RegionKey;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.util.CheckedConsumer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TestRegionHandleCache {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @Rule public final Timeout timeout = Timeout.seconds(20);
    private final ExecutorService workers = Executors.newCachedThreadPool(action -> {
        Thread thread = new Thread(action, "region-cache-test"); thread.setDaemon(true); return thread;
    });
    @After public void stopWorkers() throws Exception { workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS)); }
    private static EntryLocation3D key(int region) { return new EntryLocation3D(region * 16, -16, 0); }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IOException("Latch timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    private static <T> T result(Future<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static void rejects(IOAction action) throws Exception {
        try { action.run(); fail("Expected IOException"); } catch (IOException expected) { }
    }
    private interface IOAction { void run() throws Exception; }

    private final class Fixture {
        final RegionHandleCache cache;
        final Path directory = temporary.newFolder().toPath();
        final Map<RegionKey, FakeRegion> opened = new ConcurrentHashMap<>();
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), opens = new AtomicInteger();
        final RegionHandleCache.Provider<EntryLocation3D> provider;
        Fixture(int capacity) throws IOException {
            cache = new RegionHandleCache(capacity);
            provider = cache.provider(directory, new EntryLocation3D.Provider(), false, (keys, region) -> open(region));
        }
        FakeRegion open(RegionKey key) {
            opens.incrementAndGet(); peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            FakeRegion region = new FakeRegion(active); opened.put(key, region); return region;
        }
        FakeRegion touch(int index) throws IOException {
            provider.forRegion(key(index), region -> region.hasValue(key(index)));
            return opened.get(key(index).getRegionKey());
        }
    }

    private static class FakeRegion implements IRegion<EntryLocation3D> {
        final AtomicInteger active;
        int flushes, closes, flushFailures, closeFailures;
        boolean closed;
        CountDownLatch readEntered, readRelease, flushEntered, flushRelease;
        FakeRegion(AtomicInteger active) { this.active = active; }
        @Override public void writeValue(EntryLocation3D key, ByteBuffer data) throws IOException { if (closed) throw new IOException("closed"); }
        @Override public void writeSpecial(EntryLocation3D key, Object value) { }
        @Override public Optional<ByteBuffer> readValue(EntryLocation3D key) throws IOException {
            if (readEntered != null) { readEntered.countDown(); await(readRelease); }
            if (closed) throw new IOException("closed during read");
            return Optional.of(ByteBuffer.wrap(new byte[] {42}));
        }
        @Override public boolean hasValue(EntryLocation3D key) { return !closed; }
        @Override public void forEachKey(CheckedConsumer<? super EntryLocation3D, IOException> consumer) throws IOException { consumer.accept(key(0)); }
        @Override public void flush() throws IOException {
            flushes++;
            if (flushEntered != null) { flushEntered.countDown(); await(flushRelease); }
            if (flushFailures-- > 0) throw new IOException("flush failed");
            if (closed) throw new IOException("flush after close");
        }
        @Override public void close() throws IOException {
            closes++;
            if (closeFailures-- > 0) throw new IOException("close failed");
            if (!closed) { closed = true; active.decrementAndGet(); }
        }
    }

    @Test public void newAndExistingFilesShareThe256HandleBound() throws Exception {
        for (boolean existing : new boolean[] {false, true}) {
            Fixture f = new Fixture(256);
            for (int i = 0; i < 600; i++) {
                if (existing) {
                    Files.createFile(f.directory.resolve(key(i).getRegionKey().getName()));
                    try (IRegion<EntryLocation3D> lease = f.provider.getExistingRegion(key(i)).get()) { assertTrue(lease.hasValue(key(i))); }
                } else f.touch(i);
                assertEquals(Math.min(i + 1, 256), f.cache.size());
            }
            assertEquals(256, f.peak.get()); assertEquals(256, f.active.get());
            f.cache.close(); f.cache.close(); assertEquals(0, f.active.get()); assertEquals(0, f.cache.size());
        }
    }

    @Test public void concurrentOpenIsDeduplicatedWithoutBlockingOtherRegions() throws Exception {
        Fixture f = new Fixture(3); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        RegionHandleCache.Provider<EntryLocation3D> slow = f.cache.provider(f.directory, new EntryLocation3D.Provider(), false, (keys, region) -> {
            entered.countDown(); await(release); return f.open(region);
        });
        Future<?> one = workers.submit(() -> { slow.forRegion(key(0), r -> r.hasValue(key(0))); return null; });
        await(entered);
        Future<?> two = workers.submit(() -> { slow.forRegion(key(0), r -> r.hasValue(key(0))); return null; });
        f.touch(1); assertEquals(1, f.opens.get());
        release.countDown(); result(one); result(two);
        assertEquals(2, f.opens.get()); f.cache.close();
    }

    @Test public void failedOpenReleasesReservation() throws Exception {
        Fixture f = new Fixture(1); AtomicInteger attempts = new AtomicInteger();
        RegionHandleCache.Provider<EntryLocation3D> provider = f.cache.provider(f.directory, new EntryLocation3D.Provider(), false, (keys, region) -> {
            if (attempts.getAndIncrement() == 0) throw new IOException("open failed");
            return f.open(region);
        });
        rejects(() -> provider.getRegion(key(0))); assertEquals(0, f.cache.size());
        provider.forRegion(key(0), region -> assertTrue(region.hasValue(key(0)))); f.cache.close();
    }

    @Test public void pinnedCapacityWaitCanBeInterruptedWithoutClosingReader() throws Exception {
        Fixture f = new Fixture(1); IRegion<EntryLocation3D> lease = f.provider.getRegion(key(0));
        CountDownLatch entered = new CountDownLatch(1); AtomicInteger interrupted = new AtomicInteger();
        Thread waiter = new Thread(() -> {
            entered.countDown();
            try { f.provider.getRegion(key(1)); }
            catch (InterruptedIOException expected) { if (Thread.currentThread().isInterrupted()) interrupted.incrementAndGet(); }
            catch (IOException error) { throw new AssertionError(error); }
        });
        waiter.setDaemon(true); waiter.start(); await(entered); waiter.interrupt(); waiter.join(3000);
        assertFalse(waiter.isAlive()); assertEquals(1, interrupted.get()); assertEquals(1, f.active.get());
        assertEquals(42, lease.readValue(key(0)).get().get()); lease.close();
        f.touch(1); assertEquals(1, f.peak.get()); f.cache.close();
    }

    @Test public void nestedAdmissionAndLifecycleFailInsteadOfDeadlocking() throws Exception {
        Fixture f = new Fixture(1);
        try (IRegion<EntryLocation3D> lease = f.provider.getRegion(key(0))) {
            rejects(() -> f.provider.getRegion(key(1)));
            rejects(f.cache::flush); rejects(f.cache::close);
            lease.forEachKey(key -> { try { rejects(f.cache::flush); } catch (Exception error) { throw new IOException(error); } });
        }
        f.cache.close();
    }

    @Test public void closeWaitsForReadAndRejectsNewAdmissions() throws Exception {
        Fixture f = new Fixture(2); FakeRegion region = f.touch(0);
        region.readEntered = new CountDownLatch(1); region.readRelease = new CountDownLatch(1);
        Future<Integer> reader = workers.submit(() -> f.provider.fromRegion(key(0), r -> (int) r.readValue(key(0)).get().get()));
        await(region.readEntered);
        Future<?> close = workers.submit(() -> { f.cache.close(); return null; });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try { f.provider.forRegion(key(1), r -> { }); }
            catch (IOException closing) { break; }
            assertTrue(System.nanoTime() < deadline); Thread.yield();
        }
        assertFalse(close.isDone()); assertFalse(region.closed);
        region.readRelease.countDown(); assertEquals(Integer.valueOf(42), result(reader)); result(close);
        assertEquals(0, f.active.get()); rejects(() -> f.provider.getRegion(key(0)));
    }

    @Test public void lifecycleReentryRejectsBeforeWaitingOnAnotherCloser() throws Exception {
        Fixture f = new Fixture(1); IRegion<EntryLocation3D> lease = f.provider.getRegion(key(0));
        Future<?> closer = workers.submit(() -> { f.cache.close(); return null; });
        // Both calls must fail promptly even if the closer already holds its lifecycle lock.
        rejects(f.cache::flush); rejects(f.cache::close); lease.close(); result(closer);
    }

    @Test public void flushAndCloseAreOwnedAndVisitEachHandleOnce() throws Exception {
        Fixture a = new Fixture(4), b = new Fixture(4);
        FakeRegion a0 = a.touch(0), a1 = a.touch(1), b0 = b.touch(0);
        a.cache.flush(); assertEquals(1, a0.flushes); assertEquals(1, a1.flushes); assertEquals(0, b0.flushes);
        a.cache.close(); assertEquals(1, a0.closes); assertEquals(1, a1.closes);
        assertFalse(b0.closed); assertEquals(42, (int) b.provider.fromRegion(key(0), r -> (int) r.readValue(key(0)).get().get()));
        b.cache.close();
    }

    @Test public void slowFlushDoesNotHoldAdmissionMetadataOrOtherStorage() throws Exception {
        Fixture a = new Fixture(3), b = new Fixture(1); FakeRegion slow = a.touch(0);
        slow.flushEntered = new CountDownLatch(1); slow.flushRelease = new CountDownLatch(1);
        Future<?> flushing = workers.submit(() -> { a.cache.flush(); return null; }); await(slow.flushEntered);
        a.touch(1); b.touch(0); b.cache.flush(); b.cache.close();
        slow.flushRelease.countDown(); result(flushing); slow.flushEntered = null; a.cache.close();
    }

    @Test public void evictionKeepsRetiringHandleCountedUntilClose() throws Exception {
        Fixture f = new Fixture(1); FakeRegion old = f.touch(0);
        old.flushEntered = new CountDownLatch(1); old.flushRelease = new CountDownLatch(1);
        Future<?> admit = workers.submit(() -> { f.touch(1); return null; }); await(old.flushEntered);
        assertEquals(1, f.cache.size()); assertEquals(1, f.opens.get()); assertEquals(1, f.active.get());
        old.flushRelease.countDown(); result(admit); assertEquals(1, f.peak.get()); f.cache.close();
    }

    @Test public void failedEvictionFlushRetainsHandleForRetry() throws Exception {
        Fixture f = new Fixture(1); FakeRegion old = f.touch(0); old.flushFailures = 1;
        rejects(() -> f.touch(1)); assertEquals(0, old.closes); assertEquals(1, f.active.get());
        assertSame(old, f.touch(0)); f.touch(1); assertTrue(old.closed); f.cache.close();
    }

    @Test public void closeAggregatesFailuresRetainsCapacityAndCanRetryCleanup() throws Exception {
        Fixture f = new Fixture(3); FakeRegion a = f.touch(0), b = f.touch(1), c = f.touch(2);
        a.flushFailures = 1; b.closeFailures = 1;
        try { f.cache.close(); fail(); } catch (IOException failure) { assertEquals(1, failure.getSuppressed().length); }
        assertEquals(0, a.closes); assertEquals(1, b.closes); assertTrue(c.closed); assertEquals(2, f.cache.size());
        rejects(() -> f.provider.getRegion(key(3)));
        f.cache.close(); assertEquals(2, b.closes); assertEquals(1, b.flushes); assertEquals(0, f.active.get());
    }

    @Test public void failedCloseDuringEvictionCannotBeReopenedOrExceedCapacity() throws Exception {
        Fixture f = new Fixture(1); FakeRegion old = f.touch(0); old.closeFailures = 1;
        rejects(() -> f.touch(1)); rejects(() -> f.touch(0)); rejects(() -> f.touch(2));
        assertEquals(1, f.opens.get()); assertEquals(1, f.cache.size()); f.cache.close();
    }

    @Test public void callbackFailureAndRepeatedLeaseCloseReleaseExactlyOnce() throws Exception {
        Fixture f = new Fixture(1);
        rejects(() -> f.provider.forRegion(key(0), r -> { throw new IOException("callback failed"); }));
        IRegion<EntryLocation3D> lease = f.provider.getRegion(key(0)); lease.close(); lease.close();
        rejects(() -> lease.readValue(key(0))); f.touch(1); f.cache.close();
    }

    @Test public void concurrentChurnFlushAndReadsFinishWithinBound() throws Exception {
        Fixture f = new Fixture(4); List<Future<?>> tasks = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            final int offset = worker;
            tasks.add(workers.submit(() -> {
                for (int i = 0; i < 150; i++) {
                    EntryLocation3D key = key((i + offset) % 9);
                    assertEquals(Integer.valueOf(42), f.provider.fromRegion(key, r -> (int) r.readValue(key).get().get()));
                    if (i % 19 == 0) f.cache.flush();
                }
                return null;
            }));
        }
        for (Future<?> task : tasks) result(task);
        assertTrue(f.peak.get() <= 4); f.cache.close(); assertEquals(0, f.active.get());
    }
}
