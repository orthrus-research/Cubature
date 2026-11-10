package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.core.server.TickDiagnostics;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, chronological phase vectors; totals still include ticks beyond the storage bound. */
final class LabTickCosts {
    private static final TickDiagnostics.Phase[] PHASES = TickDiagnostics.Phase.values();
    private static final TickDiagnostics.Phase[] INSTALL_PARTS = {
            TickDiagnostics.Phase.CUBE_CONSTRUCT, TickDiagnostics.Phase.CUBE_POPULATE,
            TickDiagnostics.Phase.CUBE_FIRST_LIGHT, TickDiagnostics.Phase.CUBE_TRACK_SURFACE};
    private final TickDiagnostics.Sample[] rows;
    private final Samples complete;
    private final long[] totals = new long[PHASES.length];
    private final long[] maxima = new long[PHASES.length];
    private final long originNanos = System.nanoTime();
    private final String originUtc = Instant.now().toString();
    private int size;
    private long ticks, installedCubes, emptyCubes, tickTotal, phaseTotal, maximumAccountingError;

    LabTickCosts() { this(120000); }

    LabTickCosts(int capacity) {
        rows = new TickDiagnostics.Sample[capacity];
        complete = new Samples(capacity);
    }

    void record(TickDiagnostics.Sample sample) {
        long[] costs = sample.phaseNanos();
        if (costs.length != PHASES.length) throw new IllegalArgumentException("Unexpected tick phase count");
        complete.addAt(sample.elapsedNanos(), sample.endedNanos());
        long accounted = 0;
        for (int i = 0; i < costs.length; i++) {
            if (costs[i] < 0) throw new IllegalArgumentException("Negative tick phase duration");
            totals[i] += costs[i];
            maxima[i] = Math.max(maxima[i], costs[i]);
            accounted += costs[i];
        }
        ticks++;
        installedCubes += sample.installedCubes();
        emptyCubes += sample.emptyCubes();
        tickTotal += sample.elapsedNanos();
        phaseTotal += accounted;
        maximumAccountingError = Math.max(maximumAccountingError, Math.abs(accounted - sample.elapsedNanos()));
        if (size < rows.length) rows[size++] = sample;
    }

    Map<String, Object> summary() {
        Map<String, Object> result = new Snapshot(Arrays.copyOf(rows, size), originNanos, originUtc);
        result.put("ticks", ticks);
        result.put("storedTicks", size);
        result.put("droppedTicks", ticks - size);
        result.put("complete", new LinkedHashMap<>(complete.summary()));
        Map<String, Object> phaseSummary = new LinkedHashMap<>();
        long[][] columns = new long[PHASES.length][size];
        for (int row = 0; row < size; row++) {
            long[] costs = rows[row].phaseNanos();
            for (int phase = 0; phase < costs.length; phase++) columns[phase][row] = costs[phase];
        }
        for (int i = 0; i < PHASES.length; i++) {
            Arrays.sort(columns[i]);
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("count", ticks);
            phase.put("storedCount", size);
            phase.put("droppedCount", ticks - size);
            phase.put("totalNanos", totals[i]);
            phase.put("meanMs", ticks == 0 ? null : totals[i] / 1e6 / ticks);
            phase.put("p50Ms", Samples.percentile(columns[i], .50));
            phase.put("p95Ms", Samples.percentile(columns[i], .95));
            phase.put("p99Ms", Samples.percentile(columns[i], .99));
            phase.put("maxMs", ticks == 0 ? null : maxima[i] / 1e6);
            phase.put("percentilesCoverAllObservations", ticks == size);
            phaseSummary.put(PHASES[i].name(), phase);
        }
        result.put("phases", phaseSummary);
        result.put("installedCubes", installedCubes);
        result.put("emptyCubes", emptyCubes);
        result.put("emptyCubeShare", installedCubes == 0 ? null : (double) emptyCubes / installedCubes);
        long installNanos = 0;
        Map<String, Object> parts = new LinkedHashMap<>();
        for (TickDiagnostics.Phase phase : INSTALL_PARTS) {
            long nanos = totals[phase.ordinal()];
            installNanos += nanos;
            parts.put(phase.name(), installedCubes == 0 ? null : nanos / 1e6 / installedCubes);
        }
        result.put("installNanos", installNanos);
        result.put("installMsPerCube", installedCubes == 0 ? null : installNanos / 1e6 / installedCubes);
        result.put("installPartsMsPerCube", parts);
        result.put("tickTotalNanos", tickTotal);
        result.put("phaseTotalNanos", phaseTotal);
        result.put("maximumTickAccountingErrorNanos", maximumAccountingError);
        result.put("scope", "Exclusive server-thread phases inside MinecraftServer.tick across all worlds; OTHER includes uninstrumented work. Installed cubes count unique cube-map registrations, generated or loaded; no-storage share uses that denominator. Construction includes generated Cube construction and registration, excluding I/O-worker construction. Per-cube costs divide aggregate work by registrations in the capture; processing may cross tick boundaries. Phase percentiles cover stored prefix; totals include dropped ticks.");
        return result;
    }

    private static final class Snapshot extends LinkedHashMap<String, Object> implements LabCsvSnapshot {
        private final TickDiagnostics.Sample[] rows;
        private final long originNanos;
        private final String originUtc;

        Snapshot(TickDiagnostics.Sample[] rows, long originNanos, String originUtc) {
            this.rows = rows;
            this.originNanos = originNanos;
            this.originUtc = originUtc;
        }

        @Override public void exportCsv(Path path) throws IOException {
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                writer.write("ordinal,tick,observationEndOffsetNanos,tickNanos,installedCubes,emptyCubes");
                for (TickDiagnostics.Phase phase : PHASES) writer.write("," + phase.name());
                writer.write('\n');
                for (int i = 0; i < rows.length; i++) {
                    TickDiagnostics.Sample row = rows[i];
                    writer.write(i + "," + row.tick() + "," + (row.endedNanos() - originNanos) + ","
                            + row.elapsedNanos() + "," + row.installedCubes() + "," + row.emptyCubes());
                    for (long nanos : row.phaseNanos()) writer.write("," + nanos);
                    writer.write('\n');
                }
            }
            put("chronologicalCsv", path.getFileName().toString());
            put("timelineOriginUtc", originUtc);
            put("timelineOriginMonotonicNanos", originNanos);
        }
    }
}
