package io.github.opencubicchunks.cubicchunks.lab;
import org.junit.Test;
import static org.junit.Assert.*;
public class TestSamples {
    @Test public void emptyDurationsAreUnknown() { assertNull(new Samples().summary().get("p95Ms")); }
    @Test public void nearestRankPreservesTailStalls() {
        Samples samples = new Samples(); for (int i = 1; i <= 100; i++) samples.add(i * 1000000L);
        assertEquals(95.0, (Double) samples.summary().get("p95Ms"), 0);
        assertEquals(99.0, (Double) samples.summary().get("p99Ms"), 0);
        assertEquals(50.5, (Double) samples.summary().get("meanMs"), 0);
    }
    @Test public void boundedStorageStillCountsDroppedObservations() {
        Samples samples = new Samples(); for (int i = 0; i < 120010; i++) samples.add(1);
        assertEquals(10L, samples.summary().get("droppedCount")); assertEquals(120010L, samples.summary().get("count"));
    }
    @Test public void shortP99DoesNotRepresentTheFourLargestTicks() {
        Samples samples = new Samples();
        for (int i = 0; i < 396; i++) samples.add(1000000L);
        for (int i = 0; i < 4; i++) samples.add(9000000000L);
        assertEquals(1.0, (Double) samples.summary().get("p99Ms"), 0);
        assertEquals(9000.0, (Double) samples.summary().get("maxMs"), 0);
        assertEquals(35800000000L, samples.summary().get("excessOver50msNanos"));
    }
    @Test public void exceedancesIncludeDroppedSamplesAndUseStrictThresholds() {
        Samples samples = new Samples(2);
        samples.add(50000000); samples.add(50000001); samples.add(1000000001);
        java.util.Map<?, ?> result = samples.summary();
        java.util.Map<?, ?> counts = (java.util.Map<?, ?>) result.get("exceedanceCountsAboveNanos");
        assertEquals(2L, counts.get("50000000")); assertEquals(1L, counts.get("1000000000"));
        assertEquals(950000002L, result.get("excessOver50msNanos"));
        assertEquals(false, result.get("percentilesCoverAllObservations"));
        assertEquals(1000.000001, (Double) result.get("maxMs"), .00000001);
    }
    @Test public void csvExportKeepsChronologicalOrderAndSnapshot() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("cubiclab-samples-");
        try {
            Samples samples = new Samples(2); samples.add(900); samples.add(100);
            java.util.Map<String, Object> root = new java.util.LinkedHashMap<>(); root.put("ticks", samples.summary());
            samples.add(10000); // A summary snapshot must not grow or reorder while it is being exported.
            LabData.write(directory.resolve("server.json"), root);
            java.util.List<String> rows = java.nio.file.Files.readAllLines(directory.resolve("server-ticks.csv"));
            assertEquals(3, rows.size()); assertTrue(rows.get(1).endsWith(",900")); assertTrue(rows.get(2).endsWith(",100"));
            java.util.Map<?, ?> summary = (java.util.Map<?, ?>) root.get("ticks");
            assertEquals("server-ticks.csv", summary.get("chronologicalCsv")); assertNotNull(summary.get("timelineOriginUtc"));
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(directory)) {
                for (java.nio.file.Path entry : (Iterable<java.nio.file.Path>) entries::iterator) java.nio.file.Files.delete(entry);
            }
            java.nio.file.Files.delete(directory);
        }
    }
    @Test public void verticalRouteCoversEveryAllocatedCubeAtViewDistanceThree() {
        java.util.Set<Integer> covered = new java.util.HashSet<>();
        for (int step = 0; step <= 5; step++) {
            double y = LabRoute.height(-128, 384, step);
            assertTrue(y >= -128 && y < 384);
            int cubeY = ((int) Math.floor(y)) >> 4;
            for (int offset = -3; offset <= 3; offset++) covered.add(cubeY + offset);
        }
        for (int cubeY = -8; cubeY < 24; cubeY++) assertTrue("Unvisited cube layer " + cubeY, covered.contains(cubeY));
        assertEquals(376.0, LabRoute.height(-128, 384, 5), 0);
    }
    @Test public void extendedVerticalRoutePreservesHistoricalPosesAndReversesWithoutEndpointDuplicates() {
        double[] historical = {-120, -20.8, 78.4, 177.6, 276.8, 376};
        for (int step = 0; step < historical.length; step++) {
            assertEquals("Historical pose " + step, historical[step], LabRoute.height(-128, 384, step), 1e-12);
        }
        int[] firstRoundTrip = {0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0, 1};
        for (int step = 0; step < firstRoundTrip.length; step++) {
            assertEquals("Round-trip pose " + step, historical[firstRoundTrip[step]], LabRoute.height(-128, 384, step), 1e-12);
            if (step > 0) assertNotEquals(LabRoute.height(-128, 384, step - 1), LabRoute.height(-128, 384, step), 0);
        }
    }
    @Test public void longestSupportedVerticalWindowKeepsAllThreeHundredPosesInsideTheSameHeightRange() {
        for (int step = 0; step < 300; step++) {
            double height = LabRoute.height(-128, 384, step);
            assertTrue("Lower route bound at " + step, height >= -120);
            assertTrue("Upper route bound at " + step, height <= 376);
            assertEquals("Round-trip period at " + step, height, LabRoute.height(-128, 384, step + 10), 0);
            if (step > 0) {
                double movement = height - LabRoute.height(-128, 384, step - 1);
                int phase = step % 10;
                assertTrue("Direction at " + step, phase >= 1 && phase <= 5 ? movement > 0 : movement < 0);
                assertEquals("Historical waypoint spacing at " + step, 99.2, Math.abs(movement), 1e-12);
            }
        }
    }
    @Test public void verticalRouteUsesWideArithmeticAtIntegerBoundsAndAcceptsTheMinimumSpan() {
        int[][] bounds = {{Integer.MIN_VALUE, Integer.MAX_VALUE}, {Integer.MAX_VALUE - 16, Integer.MAX_VALUE},
                {Integer.MIN_VALUE, Integer.MIN_VALUE + 16}, {-8, 8}};
        for (int[] range : bounds) {
            for (int step = 0; step < 300; step++) {
                double height = LabRoute.height(range[0], range[1], step);
                assertTrue(Double.isFinite(height));
                assertTrue(height >= range[0] + 8.0);
                assertTrue(height <= range[1] - 8.0);
            }
            assertEquals(range[0] + 8.0, LabRoute.height(range[0], range[1], 0), 0);
            assertEquals(range[1] - 8.0, LabRoute.height(range[0], range[1], 5), 0);
            assertEquals(LabRoute.height(range[0], range[1], 3), LabRoute.height(range[0], range[1], Integer.MAX_VALUE), 0);
        }
    }
    @Test public void verticalRouteRejectsNegativeStepsAndHeightSpansWithoutBothMargins() {
        int[][] invalid = {{-128, 384, -1}, {-128, 384, Integer.MIN_VALUE}, {0, 15, 0}, {0, 0, 0},
                {1, 0, 0}, {Integer.MIN_VALUE, Integer.MIN_VALUE + 15, 0},
                {Integer.MAX_VALUE - 15, Integer.MAX_VALUE, 0}, {Integer.MAX_VALUE, Integer.MIN_VALUE, 0}};
        for (int[] route : invalid) {
            try {
                LabRoute.height(route[0], route[1], route[2]);
                fail("Invalid route accepted: " + java.util.Arrays.toString(route));
            } catch (IllegalArgumentException expected) { }
        }
    }
}
