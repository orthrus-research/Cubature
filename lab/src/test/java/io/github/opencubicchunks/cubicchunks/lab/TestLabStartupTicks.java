package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.core.server.TickDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestLabStartupTicks {
    private static final long DONE = 100_000_000_000L;
    private static final String READY = "Done (101.693s)! For help, type \"help\" or \"?\"";

    private LabStartupTicks ready(int capacity) {
        LabStartupTicks window = new LabStartupTicks(() -> DONE, capacity);
        window.reset(Thread.currentThread());
        window.observeLog(LabStartupTicks.LOGGER, READY, Thread.currentThread().getName(), Thread.currentThread());
        return window;
    }

    private TickDiagnostics.Sample tick(long ordinal, long startOffset, long duration) {
        long[] phases = new long[TickDiagnostics.Phase.values().length];
        phases[TickDiagnostics.Phase.OTHER.ordinal()] = duration;
        return new TickDiagnostics.Sample(ordinal, duration, DONE + startOffset + duration, phases, 0, 0);
    }

    private Map<?, ?> costs(Map<String, Object> summary) { return (Map<?, ?>) summary.get("tickCosts"); }

    @Test public void startBoundaryIsInclusiveAndEndBoundaryExclusive() {
        LabStartupTicks window = ready(4);
        window.record(tick(1, -10, 5));
        window.record(tick(2, 0, 1));
        window.record(tick(3, LabStartupTicks.WINDOW_NANOS - 2, 1));
        window.record(tick(4, LabStartupTicks.WINDOW_NANOS, 1));
        Map<String, Object> result = window.summary();
        assertEquals(true, result.get("valid"));
        assertEquals(true, result.get("complete"));
        assertEquals(1, result.get("doneEvents"));
        assertEquals(2L, costs(result).get("ticks"));
        assertEquals(DONE + LabStartupTicks.WINDOW_NANOS + 1, result.get("observedThroughMonotonicNanos"));
    }

    @Test public void longCrossingTickKeepsItsWholeDuration() {
        LabStartupTicks window = ready(4);
        window.record(tick(1, 0, 1_000_000));
        window.record(tick(2, 59_000_000_000L, 36_000_000_000L));
        Map<String, Object> result = window.summary();
        assertEquals(true, result.get("valid"));
        Map<?, ?> complete = (Map<?, ?>) costs(result).get("complete");
        assertEquals(36000.0, (Double) complete.get("p95Ms"), 0);
        assertEquals(36000.0, (Double) complete.get("maxMs"), 0);
        assertEquals(36_001_000_000L, costs(result).get("tickTotalNanos"));
        window.record(tick(3, 96_000_000_000L, 1));
        assertEquals(2L, costs(window.summary()).get("ticks"));
    }

    @Test public void missingDoneAndShortWindowAreExplicitFailures() {
        LabStartupTicks window = new LabStartupTicks(() -> DONE, 4);
        window.reset(Thread.currentThread());
        window.record(tick(1, 0, LabStartupTicks.WINDOW_NANOS));
        assertEquals(false, window.summary().get("valid"));
        assertEquals(false, window.summary().get("doneObserved"));
        assertEquals("No dedicated-server ready message", window.summary().get("failure"));
        window = ready(4);
        window.record(tick(1, 0, 1));
        assertEquals(false, window.summary().get("valid"));
        assertEquals("Server stopped before the startup window completed", window.summary().get("failure"));
    }

    @Test public void duplicateOrChangedReadyMessageCannotValidate() {
        LabStartupTicks window = ready(4);
        window.observeLog(LabStartupTicks.LOGGER, READY, Thread.currentThread().getName(), Thread.currentThread());
        window.record(tick(1, 0, LabStartupTicks.WINDOW_NANOS));
        assertEquals(false, window.summary().get("valid"));
        assertEquals(2, window.summary().get("doneEvents"));
        assertEquals("More than one dedicated-server ready message", window.summary().get("failure"));
        window.reset(Thread.currentThread());
        window.observeLog(LabStartupTicks.LOGGER, "Done (something else)", Thread.currentThread().getName(), Thread.currentThread());
        assertEquals("Unexpected dedicated-server ready message", window.summary().get("failure"));
    }

    @Test public void otherLoggersAreIgnoredAndAsyncReadyIsRejected() {
        LabStartupTicks window = new LabStartupTicks(() -> DONE, 4);
        window.reset(Thread.currentThread());
        window.observeLog("another.logger", READY, Thread.currentThread().getName(), Thread.currentThread());
        assertEquals(0, window.summary().get("doneEvents"));
        window.observeLog(LabStartupTicks.LOGGER, READY, Thread.currentThread().getName(), new Thread("async"));
        assertEquals(false, window.summary().get("valid"));
        assertEquals("Ready message was not observed synchronously on the server thread", window.summary().get("failure"));
    }

    @Test public void mismatchedEventThreadNameIsRejectedEvenOnOwner() {
        LabStartupTicks window = new LabStartupTicks(() -> DONE, 4);
        window.reset(Thread.currentThread());
        window.observeLog(LabStartupTicks.LOGGER, READY, "another thread", Thread.currentThread());
        assertEquals(false, window.summary().get("valid"));
        assertEquals(false, window.summary().get("doneObserved"));
    }

    @Test public void aTickFromAnotherThreadCannotEnterTheStartupWindow() {
        LabStartupTicks window = new LabStartupTicks(() -> DONE, 4);
        Thread owner = new Thread("server-owner");
        window.reset(owner);
        window.observeLog(LabStartupTicks.LOGGER, READY, owner.getName(), owner);
        window.record(tick(1, 0, LabStartupTicks.WINDOW_NANOS));
        assertEquals(false, window.summary().get("valid"));
        assertEquals("Tick sample came from another thread", window.summary().get("failure"));
        assertEquals(0L, costs(window.summary()).get("ticks"));
    }

    @Test public void overflowCannotProduceAPartialSuccessAndResetStartsAFreshEpoch() {
        LabStartupTicks window = ready(1);
        window.record(tick(1, 0, 1));
        window.record(tick(2, 1, LabStartupTicks.WINDOW_NANOS));
        assertEquals(false, window.summary().get("valid"));
        assertEquals(1L, costs(window.summary()).get("droppedTicks"));
        assertEquals("Startup ticks exceeded the storage bound", window.summary().get("failure"));
        window.reset(Thread.currentThread());
        assertEquals(false, window.summary().get("complete"));
        assertEquals(0, window.summary().get("doneEvents"));
        assertEquals(0L, costs(window.summary()).get("ticks"));
        window.observeLog(LabStartupTicks.LOGGER, READY, Thread.currentThread().getName(), Thread.currentThread());
        window.record(tick(3, 0, LabStartupTicks.WINDOW_NANOS));
        assertEquals(true, window.summary().get("valid"));
    }

    @Test public void completeWindowWithNoEligibleTicksCannotValidate() {
        LabStartupTicks window = ready(4);
        window.record(tick(1, LabStartupTicks.WINDOW_NANOS, 1));
        assertEquals(true, window.summary().get("complete"));
        assertEquals(false, window.summary().get("valid"));
        assertEquals("No startup ticks observed", window.summary().get("failure"));
    }

    @Test public void exportedRowsUseTheExactCoreDurationAndWindowAnchor() throws Exception {
        Path directory = Files.createTempDirectory("cubiclab-startup-ticks-");
        try {
            LabStartupTicks window = ready(4);
            window.record(tick(7, 0, 1_000_000));
            window.record(tick(8, 59_000_000_000L, 36_000_000_000L));
            Map<String, Object> result = window.summary();
            LabData.write(directory.resolve("startup.json"), result);
            List<String> rows = Files.readAllLines(directory.resolve("startup-tickCosts.csv"));
            assertEquals(3, rows.size());
            assertEquals("36000000000", rows.get(2).split(",")[3]);
            assertEquals(DONE, result.get("doneMonotonicNanos"));
            assertEquals("startup-tickCosts.csv", costs(result).get("chronologicalCsv"));
        } finally {
            try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                for (Path entry : (Iterable<Path>) entries::iterator) Files.delete(entry);
            }
            Files.delete(directory);
        }
    }

    @Test public void nonAdditiveMinecraftLoggerDeliversDoneAndShutdownDetachesTheObserver() throws Exception {
        Path directory = Files.createTempDirectory("cubiclab-startup-logging-");
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig previous = configuration.getLoggers().get("net.minecraft");
        LoggerConfig minecraft = new LoggerConfig("net.minecraft", Level.INFO, false);
        LabStartupTicks window = new LabStartupTicks(() -> DONE, 4);
        configuration.removeLogger("net.minecraft");
        configuration.addLogger("net.minecraft", minecraft);
        context.updateLoggers();
        try {
            assertSame(minecraft, configuration.getLoggerConfig(LabStartupTicks.LOGGER));
            window.open(directory);
            org.apache.logging.log4j.core.Logger actual =
                    (org.apache.logging.log4j.core.Logger) LogManager.getLogger(LabStartupTicks.LOGGER);
            assertSame("Dedicated logger context", context, actual.getContext());
            assertTrue("Dedicated logger INFO disabled at " + actual.getLevel(), actual.isInfoEnabled());
            actual.info(READY);
            window.record(tick(1, 0, LabStartupTicks.WINDOW_NANOS));
            assertEquals(String.valueOf(window.summary().get("failure")), true, window.summary().get("valid"));
            assertFalse(minecraft.getAppenders().isEmpty());
            window.finish();
            assertTrue(minecraft.getAppenders().isEmpty());
            try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
                List<Path> epochs = entries.toList();
                assertEquals(1, epochs.size());
                Path epoch = epochs.get(0);
                assertTrue(Files.readString(epoch.resolve("startup.json")).contains("\"valid\": true"));
                assertEquals(2, Files.readAllLines(epoch.resolve("startup-tickCosts.csv")).size());
            }
        } finally {
            window.finish();
            configuration.removeLogger("net.minecraft");
            if (previous != null) configuration.addLogger("net.minecraft", previous);
            context.updateLoggers();
            try (java.util.stream.Stream<Path> entries = Files.walk(directory)) {
                for (Path entry : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator) Files.delete(entry);
            }
        }
    }

    @Test public void exportFailurePreservesAnExistingShutdownFailure() throws Exception {
        Path file = Files.createTempFile("cubiclab-startup-export-", ".file");
        LabStartupTicks window = new LabStartupTicks();
        try {
            window.open(file);
            RuntimeException primary = new IllegalStateException("Fixture cleanup failed");
            window.finish(primary);
            assertEquals(1, primary.getSuppressed().length);
            assertTrue(primary.getSuppressed()[0].getMessage().startsWith("Cannot preserve lab result"));
            window.open(file);
            try {
                window.finish();
                fail("Export failure without a primary failure must be reported");
            } catch (IllegalStateException exportFailure) {
                assertTrue(exportFailure.getMessage().startsWith("Cannot preserve lab result"));
            }
        } finally {
            window.finish();
            Files.delete(file);
        }
    }
}
