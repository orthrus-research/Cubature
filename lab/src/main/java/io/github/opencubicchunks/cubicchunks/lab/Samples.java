package io.github.opencubicchunks.cubicchunks.lab;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Bounded duration observations; dropped observations are reported instead of hidden. */
public final class Samples {
    private final long[] values, ends;
    private final long originNanos = System.nanoTime();
    private final String originUtc = Instant.now().toString();
    private static final long[] THRESHOLDS = {16666667, 33333333, 50000000, 100000000, 250000000, 1000000000};
    private final long[] exceedances = new long[THRESHOLDS.length];
    private int size;
    private long totalCount, totalNs, maximumNs, excessOver50ms;
    public Samples() { this(120000); }
    Samples(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Sample capacity must be positive");
        values = new long[capacity]; ends = new long[capacity];
    }
    public synchronized void add(long ns) { addAt(ns, System.nanoTime()); }
    synchronized void addAt(long ns, long endedNanos) {
        if (ns < 0) throw new IllegalArgumentException("Negative duration");
        totalCount++; totalNs += ns; maximumNs = Math.max(maximumNs, ns);
        for (int i = 0; i < THRESHOLDS.length; i++) if (ns > THRESHOLDS[i]) exceedances[i]++;
        if (ns > 50000000) excessOver50ms += ns - 50000000;
        if (size < values.length) { values[size] = ns; ends[size] = endedNanos - originNanos; size++; }
    }
    public synchronized Map<String, Object> summary() {
        long[] sorted = Arrays.copyOf(values, size);
        Arrays.sort(sorted);
        Map<String, Object> result = new Summary(this, Arrays.copyOf(values, size), Arrays.copyOf(ends, size));
        result.put("count", totalCount); result.put("storedCount", size);
        result.put("droppedCount", totalCount - size); result.put("totalNanos", totalNs);
        result.put("meanMs", totalCount == 0 ? null : totalNs / 1e6 / totalCount);
        result.put("p50Ms", percentile(sorted, .50)); result.put("p95Ms", percentile(sorted, .95));
        result.put("p99Ms", percentile(sorted, .99)); result.put("maxMs", totalCount == 0 ? null : maximumNs / 1e6);
        result.put("percentileEstimator", "nearest-rank ceil(q*storedCount), without interpolation");
        result.put("percentilesCoverAllObservations", totalCount == size);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (int i = 0; i < THRESHOLDS.length; i++) counts.put(Long.toString(THRESHOLDS[i]), exceedances[i]);
        result.put("exceedanceCountsAboveNanos", counts); result.put("excessOver50msNanos", excessOver50ms);
        result.put("stallScope", "Sum of per-observation duration above 50 ms; not exclusive wall time for overlapping operations");
        return result;
    }
    /** Snapshot-backed map: Gson writes ordinary fields; LabData preserves raw CSV after capture. */
    private static final class Summary extends LinkedHashMap<String, Object> implements LabCsvSnapshot {
        final Samples owner;
        final long[] durations, offsets;
        Summary(Samples owner, long[] durations, long[] offsets) {
            this.owner = owner; this.durations = durations; this.offsets = offsets;
        }
        public void exportCsv(Path path) throws IOException {
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                writer.write("ordinal,observationEndOffsetNanos,durationNanos\n");
                for (int i = 0; i < durations.length; i++) {
                    writer.write(Integer.toString(i)); writer.write(','); writer.write(Long.toString(offsets[i]));
                    writer.write(','); writer.write(Long.toString(durations[i])); writer.write('\n');
                }
            }
            put("chronologicalCsv", path.getFileName().toString()); put("timelineOriginUtc", owner.originUtc);
            put("timelineOriginMonotonicNanos", owner.originNanos);
            put("timelineScope", "Chronological prefix; end is sample-observation time, duration comes from caller. UTC/monotonic anchor captured consecutively; not a cross-JVM request identifier.");
        }
    }
    static void exportSummaries(Path directory, String stem, Object value) throws IOException {
        if (value instanceof LabCsvSnapshot) ((LabCsvSnapshot) value).exportCsv(directory.resolve(stem + ".csv"));
        else if (value instanceof Map) {
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet())
                exportSummaries(directory, stem + "-" + item.getKey().toString().replaceAll("[^A-Za-z0-9_-]", "_"), item.getValue());
        } else if (value instanceof Iterable) {
            int i = 0; for (Object item : (Iterable<?>) value) exportSummaries(directory, stem + "-" + i++, item);
        }
    }
    static Double percentile(long[] sorted, double quantile) {
        return sorted.length == 0 ? null : sorted[(int) Math.ceil(quantile * sorted.length) - 1] / 1e6;
    }
}
