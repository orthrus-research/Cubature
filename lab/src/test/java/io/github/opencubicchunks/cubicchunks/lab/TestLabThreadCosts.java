package io.github.opencubicchunks.cubicchunks.lab;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestLabThreadCosts {
    private static volatile byte[] retained;
    private static volatile long checksum;

    @Test public void recordsLiveWorkerCountersAndDoesNotCallExitedWorkersZeroCost() throws Exception {
        String previous = System.getProperty("cubiclab.threadCosts");
        System.setProperty("cubiclab.threadCosts", "true");
        CountDownLatch ready = new CountDownLatch(1), work = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            ready.countDown();
            try {
                work.await();
                retained = new byte[1024 * 1024];
                long sum = 0;
                for (int i = 0; i < 500000; i++) sum = (sum * 31) ^ i;
                checksum = sum;
                completed.countDown();
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "DH-Chunk Update Queue [counter-test]");
        try {
            worker.start();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            LabThreadCosts costs = new LabThreadCosts();
            work.countDown();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            Map<String, Object> row = row(costs.finish());
            assertEquals(Boolean.TRUE, row.get("available"));
            assertTrue(((Number) row.get("cpuNanos")).longValue() >= 0);
            assertTrue(((Number) row.get("allocatedBytes")).longValue() >= retained.length);
            release.countDown();
            worker.join(10000);
            assertFalse(worker.isAlive());
            row = row(costs.finish());
            assertEquals(Boolean.FALSE, row.get("available"));
            assertFalse(row.containsKey("cpuNanos"));
            assertFalse(row.containsKey("allocatedBytes"));
        } finally {
            work.countDown();
            release.countDown();
            worker.join(10000);
            if (previous == null) System.clearProperty("cubiclab.threadCosts");
            else System.setProperty("cubiclab.threadCosts", previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> result) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("threads");
        return rows.stream().filter(value -> value.get("name").equals("DH-Chunk Update Queue [counter-test]"))
                .findFirst().orElseThrow(AssertionError::new);
    }
}
