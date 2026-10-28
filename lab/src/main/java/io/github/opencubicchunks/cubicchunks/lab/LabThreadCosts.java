package io.github.opencubicchunks.cubicchunks.lab;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Two counter reads per named worker, outside per-frame/tick hooks. */
final class LabThreadCosts {
    private final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private final com.sun.management.ThreadMXBean allocation =
            cpu instanceof com.sun.management.ThreadMXBean ? (com.sun.management.ThreadMXBean) cpu : null;
    private final boolean enabled = Boolean.getBoolean("cubiclab.threadCosts");
    private final Map<Long, String> names = new LinkedHashMap<>();
    private final Map<Long, long[]> initial = new LinkedHashMap<>();
    private final long started = System.nanoTime();

    LabThreadCosts() {
        if (!enabled) return;
        for (long id : cpu.getAllThreadIds()) {
            ThreadInfo info = cpu.getThreadInfo(id);
            if (info != null && info.getThreadName().startsWith("DH-Chunk Update Queue [")) {
                names.put(id, info.getThreadName());
                initial.put(id, counters(id));
            }
        }
    }

    private long[] counters(long id) {
        return new long[] {
            cpu.isThreadCpuTimeSupported() && cpu.isThreadCpuTimeEnabled() ? cpu.getThreadCpuTime(id) : -1,
            allocation != null && allocation.isThreadAllocatedMemorySupported() && allocation.isThreadAllocatedMemoryEnabled()
                    ? allocation.getThreadAllocatedBytes(id) : -1
        };
    }

    Map<String, Object> finish() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        if (!enabled) return result;
        result.put("elapsedNanos", System.nanoTime() - started);
        result.put("scope", "MXBean CPU and allocated-byte counter deltas for DH chunk-update workers alive at both boundaries. "
                + "Excludes workers created after the start, all other threads, retained heap and presentation latency. "
                + "Counter reads are sequential, not an atomic cross-thread snapshot.");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, String> entry : names.entrySet()) {
            long id = entry.getKey();
            ThreadInfo info = cpu.getThreadInfo(id);
            long[] before = initial.get(id), after = counters(id);
            boolean available = info != null && info.getThreadName().equals(entry.getValue())
                    && before[0] >= 0 && before[1] >= 0 && after[0] >= before[0] && after[1] >= before[1];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threadId", id);
            row.put("name", entry.getValue());
            row.put("available", available);
            if (available) {
                row.put("cpuNanos", after[0] - before[0]);
                row.put("allocatedBytes", after[1] - before[1]);
            }
            rows.add(row);
        }
        result.put("threads", rows);
        return result;
    }
}
