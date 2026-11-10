package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.core.server.TickDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestLabTickCosts {
    private TickDiagnostics.Sample sample(long tick, long construct, long other, int cubes, int empty) {
        long[] phases = new long[TickDiagnostics.Phase.values().length];
        phases[TickDiagnostics.Phase.CUBE_CONSTRUCT.ordinal()] = construct;
        phases[TickDiagnostics.Phase.OTHER.ordinal()] = other;
        return new TickDiagnostics.Sample(tick, construct + other, System.nanoTime(), phases, cubes, empty);
    }

    @Test public void allTickTotalsSurviveBoundedChronologicalStorage() {
        LabTickCosts costs = new LabTickCosts(2);
        costs.record(sample(7, 2000000, 1000000, 2, 1));
        costs.record(sample(8, 4000000, 2000000, 2, 0));
        costs.record(sample(9, 6000000, 3000000, 2, 2));
        Map<String, Object> result = costs.summary();
        assertEquals(3L, result.get("ticks"));
        assertEquals(2, result.get("storedTicks"));
        assertEquals(1L, result.get("droppedTicks"));
        assertEquals(18000000L, result.get("tickTotalNanos"));
        assertEquals(result.get("tickTotalNanos"), result.get("phaseTotalNanos"));
        assertEquals(0L, result.get("maximumTickAccountingErrorNanos"));
        assertEquals(6L, result.get("installedCubes"));
        assertEquals(0.5, (Double) result.get("emptyCubeShare"), 0);
        assertEquals(2.0, (Double) result.get("installMsPerCube"), 0);
        Map<?, ?> phases = (Map<?, ?>) result.get("phases");
        Map<?, ?> construct = (Map<?, ?>) phases.get("CUBE_CONSTRUCT");
        assertEquals(4.0, (Double) construct.get("meanMs"), 0);
        assertEquals(4.0, (Double) construct.get("p95Ms"), 0);
        assertEquals(false, construct.get("percentilesCoverAllObservations"));
    }

    @Test public void unknownPerCubeCostIsNotZeroWhenNothingWasInstalled() {
        Map<String, Object> result = new LabTickCosts(2).summary();
        assertNull(result.get("emptyCubeShare"));
        assertNull(result.get("installMsPerCube"));
        assertNull(((Map<?, ?>) result.get("installPartsMsPerCube")).get("CUBE_CONSTRUCT"));
    }

    @Test public void csvKeepsPhaseVectorsInTickOrderAndFreezesTheSummary() throws Exception {
        Path directory = Files.createTempDirectory("cubiclab-tick-costs-");
        try {
            LabTickCosts costs = new LabTickCosts(2);
            costs.record(sample(8, 200, 100, 2, 1));
            Map<String, Object> snapshot = costs.summary();
            costs.record(sample(9, 400, 200, 2, 0));
            LabData.write(directory.resolve("ticks.json"), snapshot);
            List<String> rows = Files.readAllLines(directory.resolve("ticks.csv"));
            assertEquals(2, rows.size());
            assertTrue(rows.get(0).contains(",CUBE_CONSTRUCT,"));
            String[] columns = rows.get(1).split(",");
            assertEquals("8", columns[1]);
            assertEquals("300", columns[3]);
            long sum = 0;
            for (int i = 6; i < columns.length; i++) sum += Long.parseLong(columns[i]);
            assertEquals(300L, sum);
            assertEquals("ticks.csv", snapshot.get("chronologicalCsv"));
        } finally {
            try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                for (Path entry : (Iterable<Path>) entries::iterator) Files.delete(entry);
            }
            Files.delete(directory);
        }
    }
}
