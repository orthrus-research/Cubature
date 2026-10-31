package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestLivePermits {
    @Test(timeout = 10_000) public void atMostThePermitCountRuns() throws Exception {
        LivePermits permits = new LivePermits(2);
        AtomicInteger running = new AtomicInteger(), most = new AtomicInteger();
        Thread[] holders = new Thread[6];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = new Thread(() -> {
                try {
                    permits.acquire();
                    try {
                        most.accumulateAndGet(running.incrementAndGet(), Math::max);
                        Thread.sleep(20);
                        running.decrementAndGet();
                    } finally {
                        permits.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            holders[i].start();
        }
        for (Thread holder : holders) holder.join();
        assertEquals(2, most.get());
        assertEquals(0, permits.active());
    }

    @Test(timeout = 10_000) public void aCutStopsHoldersAtTheirNextCheckpoint() throws Exception {
        LivePermits permits = new LivePermits(3);
        CountDownLatch started = new CountDownLatch(3);
        AtomicInteger working = new AtomicInteger();
        Thread[] holders = new Thread[3];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = new Thread(() -> {
                try {
                    permits.acquire();
                    started.countDown();
                    for (int row = 0; row < 400; row++) {
                        permits.checkpoint();
                        working.incrementAndGet();
                        Thread.sleep(1);
                        working.decrementAndGet();
                    }
                    permits.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            holders[i].start();
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        permits.set(1);
        Thread.sleep(50);
        // Within a few rows only one holder still works; the others wait at a checkpoint.
        assertEquals(1, permits.active());
        for (int i = 0; i < 50; i++) {
            assertTrue("At most one holder works after the cut", working.get() <= 1);
            Thread.sleep(2);
        }
        permits.set(3);
        for (Thread holder : holders) holder.join();
        assertEquals(0, permits.active());
    }

    @Test(timeout = 10_000) public void zeroPermitsHoldEveryoneUntilARaise() throws Exception {
        LivePermits permits = new LivePermits(0);
        CountDownLatch done = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                permits.acquire();
                permits.release();
                done.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertTrue("Held while there are no permits", !done.await(100, TimeUnit.MILLISECONDS));
        permits.set(1);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
