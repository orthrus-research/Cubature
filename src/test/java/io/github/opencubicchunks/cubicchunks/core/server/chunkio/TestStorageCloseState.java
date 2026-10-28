package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestStorageCloseState {
    @Test
    public void successfulCloseIsIdempotentAndEndsAdmission() throws Exception {
        List<String> actions = new ArrayList<>();
        StorageCloseState state = new StorageCloseState(() -> actions.add("backend"),
                () -> actions.add("writer"), false);
        state.requireOpen();
        state.close();
        state.close();
        assertEquals(Arrays.asList("backend", "writer"), actions);
        assertEquals(StorageCloseState.State.CLOSED, state.state());
        assertNull(state.failure());
        assertThrows(IllegalStateException.class, state::requireOpen);
    }

    @Test
    public void retryableBackendRetainsFailureAndOnlyRepeatsUnreleasedWork() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        IOException failure = new IOException("channel remains owned");
        StorageCloseState state = new StorageCloseState(() -> {
            if (closes.incrementAndGet() == 1) {
                throw failure;
            }
        }, stops::incrementAndGet, true);
        assertSame(failure, assertThrows(IOException.class, state::close));
        assertEquals(StorageCloseState.State.FAILED, state.state());
        assertSame(failure, state.failure());
        assertSame(failure, assertThrows(IllegalStateException.class, state::requireOpen).getCause());
        state.close();
        assertEquals(2, closes.get());
        assertEquals(1, stops.get());
        assertEquals(StorageCloseState.State.CLOSED, state.state());
        assertNull(state.failure());
    }

    @Test
    public void legacyProviderNeverReceivesAnUnsafeSecondClose() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        IOException failure = new IOException("single-attempt provider");
        StorageCloseState state = new StorageCloseState(() -> {
            closes.incrementAndGet();
            throw failure;
        }, stops::incrementAndGet, false);
        assertSame(failure, assertThrows(IOException.class, state::close));
        assertSame(failure, assertThrows(IOException.class, state::close).getCause());
        assertEquals(1, closes.get());
        assertEquals(1, stops.get());
        assertEquals(StorageCloseState.State.FAILED, state.state());
    }

    @Test
    public void writerFailureRetriesOnlyWriterAfterSuccessfulBackendClose() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("writer release failed");
        StorageCloseState state = new StorageCloseState(closes::incrementAndGet, () -> {
            if (stops.incrementAndGet() == 1) {
                throw failure;
            }
        }, false);
        assertSame(failure, assertThrows(IOException.class, state::close).getCause());
        state.close();
        assertEquals(1, closes.get());
        assertEquals(2, stops.get());
        assertEquals(StorageCloseState.State.CLOSED, state.state());
    }

    @Test
    public void bothFailuresPreservePrimaryAndStillAttemptEveryRelease() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        IOException backendFailure = new IOException("backend");
        RuntimeException writerFailure = new IllegalStateException("writer");
        StorageCloseState state = new StorageCloseState(() -> {
            if (closes.incrementAndGet() == 1) {
                throw backendFailure;
            }
        }, () -> {
            if (stops.incrementAndGet() == 1) {
                throw writerFailure;
            }
        }, true);
        IOException reported = assertThrows(IOException.class, state::close);
        assertSame(backendFailure, reported);
        assertEquals(1, reported.getSuppressed().length);
        assertSame(writerFailure, reported.getSuppressed()[0].getCause());
        state.close();
        assertEquals(2, closes.get());
        assertEquals(2, stops.get());
        assertEquals(StorageCloseState.State.CLOSED, state.state());
    }

    @Test
    public void failedLegacyBackendDoesNotPreventRetryOfWriterCleanup() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        StorageCloseState state = new StorageCloseState(() -> {
            closes.incrementAndGet();
            throw new IOException("legacy close failed");
        }, () -> {
            if (stops.incrementAndGet() == 1) {
                throw new IllegalStateException("writer stop failed");
            }
        }, false);
        assertThrows(IOException.class, state::close);
        assertThrows(IOException.class, state::close);
        assertEquals(1, closes.get());
        assertEquals(2, stops.get());
        assertEquals(StorageCloseState.State.FAILED, state.state());
    }

    @Test
    public void uncheckedBackendFailureIsRetainedWithItsCause() {
        RuntimeException failure = new IllegalArgumentException("backend contract failure");
        AtomicInteger stops = new AtomicInteger();
        StorageCloseState state = new StorageCloseState(() -> {
            throw failure;
        }, stops::incrementAndGet, true);
        assertSame(failure, assertThrows(IOException.class, state::close).getCause());
        assertEquals(1, stops.get());
        assertEquals(StorageCloseState.State.FAILED, state.state());
    }

    @Test
    public void fatalBackendFailureIsRethrownAfterWriterCleanup() {
        AssertionError failure = new AssertionError("backend fatal");
        AtomicInteger stops = new AtomicInteger();
        StorageCloseState state = new StorageCloseState(() -> {
            throw failure;
        }, stops::incrementAndGet, true);
        assertSame(failure, assertThrows(AssertionError.class, state::close));
        assertSame(failure, state.failure().getCause());
        assertEquals(1, stops.get());
        assertEquals(StorageCloseState.State.FAILED, state.state());
    }

    @Test
    public void identicalFatalCleanupFailureCannotPoisonStateWithSelfSuppression() {
        AssertionError failure = new AssertionError("shared fatal failure");
        StorageCloseState state = new StorageCloseState(() -> {
            throw failure;
        }, () -> {
            throw failure;
        }, true);
        assertSame(failure, assertThrows(AssertionError.class, state::close));
        assertEquals(StorageCloseState.State.FAILED, state.state());
        assertSame(failure, state.failure().getCause());
    }

    @Test
    public void callbackReentryCannotReportSuccessOrReopenAdmission() throws Exception {
        AtomicReference<StorageCloseState> holder = new AtomicReference<>();
        AtomicInteger closes = new AtomicInteger();
        StorageCloseState state = new StorageCloseState(() -> {
            assertEquals(StorageCloseState.State.CLOSING, holder.get().state());
            assertThrows(IllegalStateException.class, holder.get()::requireOpen);
            if (closes.incrementAndGet() == 1) {
                holder.get().close();
            }
        }, () -> { }, true);
        holder.set(state);
        assertThrows(IOException.class, state::close);
        assertEquals(StorageCloseState.State.FAILED, state.state());
        state.close();
        assertEquals(StorageCloseState.State.CLOSED, state.state());
    }

    @Test
    public void concurrentCallersShareOneSuccessfulRelease() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StorageCloseState state = new StorageCloseState(() -> {
            closes.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test close deadline");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }, () -> { }, false);
        Runnable close = () -> {
            try {
                state.close();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        };
        Thread first = new Thread(close);
        Thread second = new Thread(close);
        first.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, state::requireOpen);
            second.start();
        } finally {
            release.countDown();
            first.join(5000);
            second.join(5000);
        }
        assertTrue(!first.isAlive() && !second.isAlive());
        assertNull(failure.get());
        assertEquals(1, closes.get());
        assertEquals(StorageCloseState.State.CLOSED, state.state());
    }
}
