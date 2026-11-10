package io.github.opencubicchunks.cubicchunks.core.server;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestTickDiagnostics {
    @Test public void nestedCostsAreExclusiveAndExactlyCoverTheTick() {
        AtomicLong clock = new AtomicLong(100);
        TickDiagnostics.Recorder recorder = new TickDiagnostics.Recorder(clock::get);
        recorder.beginTick();
        clock.addAndGet(10);
        recorder.enter(TickDiagnostics.Phase.SEND);
        clock.addAndGet(20);
        recorder.enter(TickDiagnostics.Phase.PACKET_ENCODE);
        clock.addAndGet(30);
        recorder.exit();
        clock.addAndGet(40);
        recorder.exit();
        clock.addAndGet(50);
        TickDiagnostics.Sample sample = recorder.endTick();
        assertEquals(150, sample.elapsedNanos());
        assertEquals(60, cost(sample, TickDiagnostics.Phase.OTHER));
        assertEquals(60, cost(sample, TickDiagnostics.Phase.SEND));
        assertEquals(30, cost(sample, TickDiagnostics.Phase.PACKET_ENCODE));
        assertEquals(sample.elapsedNanos(), Arrays.stream(sample.phaseNanos()).sum());
    }

    @Test public void aWorkerCannotChargeOrCountInsideTheServerTick() throws Exception {
        AtomicLong clock = new AtomicLong();
        TickDiagnostics.Recorder recorder = new TickDiagnostics.Recorder(clock::get);
        recorder.beginTick();
        Thread worker = new Thread(() -> {
            recorder.enter(TickDiagnostics.Phase.CUBE_CONSTRUCT);
            recorder.installedCube(true);
            recorder.exit();
        });
        worker.start();
        worker.join();
        recorder.installedCube(false);
        recorder.installedCube(true);
        clock.set(70);
        TickDiagnostics.Sample sample = recorder.endTick();
        assertEquals(70, cost(sample, TickDiagnostics.Phase.OTHER));
        assertEquals(0, cost(sample, TickDiagnostics.Phase.CUBE_CONSTRUCT));
        assertEquals(2, sample.installedCubes());
        assertEquals(1, sample.emptyCubes());
    }

    @Test public void endingAThrownTickClearsOwnerAndUnfinishedNestedPhases() {
        AtomicLong clock = new AtomicLong();
        TickDiagnostics.Recorder recorder = new TickDiagnostics.Recorder(clock::get);
        try {
            recorder.beginTick();
            recorder.enter(TickDiagnostics.Phase.ARRIVALS);
            clock.set(12);
            throw new IllegalStateException("generation failed");
        } catch (IllegalStateException expected) {
            assertEquals("generation failed", expected.getMessage());
        } finally {
            assertEquals(12, cost(recorder.endTick(), TickDiagnostics.Phase.ARRIVALS));
        }
        recorder.enter(TickDiagnostics.Phase.SEND);
        recorder.exit();
        recorder.beginTick();
        clock.set(20);
        TickDiagnostics.Sample next = recorder.endTick();
        assertEquals(2, next.tick());
        assertEquals(8, cost(next, TickDiagnostics.Phase.OTHER));
        assertEquals(0, cost(next, TickDiagnostics.Phase.ARRIVALS));
        assertEquals(0, next.installedCubes());
    }

    @Test public void repeatedPhasesAndDeepReentryStillChargeOnce() {
        AtomicLong clock = new AtomicLong();
        TickDiagnostics.Recorder recorder = new TickDiagnostics.Recorder(clock::get);
        recorder.beginTick();
        for (int i = 0; i < 32; i++) {
            recorder.enter(TickDiagnostics.Phase.CUBE_CONSTRUCT);
            clock.incrementAndGet();
        }
        for (int i = 0; i < 32; i++) {
            recorder.exit();
            clock.incrementAndGet();
        }
        TickDiagnostics.Sample sample = recorder.endTick();
        assertEquals(63, cost(sample, TickDiagnostics.Phase.CUBE_CONSTRUCT));
        assertEquals(1, cost(sample, TickDiagnostics.Phase.OTHER));
        assertEquals(64, Arrays.stream(sample.phaseNanos()).sum());
    }

    @Test public void publishedPhaseVectorsCannotBeChangedByReadersOrLaterTicks() {
        AtomicLong clock = new AtomicLong();
        TickDiagnostics.Recorder recorder = new TickDiagnostics.Recorder(clock::get);
        recorder.beginTick();
        clock.set(11);
        TickDiagnostics.Sample sample = recorder.endTick();
        sample.phaseNanos()[TickDiagnostics.Phase.OTHER.ordinal()] = 1000;
        recorder.beginTick();
        clock.set(15);
        recorder.endTick();
        assertEquals(11, cost(sample, TickDiagnostics.Phase.OTHER));
    }

    @Test public void aNewServerHasNoPreviousHistoryAndStartsItsOwnTickSequence() {
        AtomicInteger callbacks = new AtomicInteger();
        TickDiagnostics.Observer previous = TickDiagnostics.setObserver(sample -> callbacks.incrementAndGet());
        try {
            TickDiagnostics.reset();
            TickDiagnostics.beginTick();
            TickDiagnostics.installedCube(true);
            TickDiagnostics.endTick();
            assertEquals(1, TickDiagnostics.recent().length);
            assertEquals(1, TickDiagnostics.latest().installedCubes());
            TickDiagnostics.reset();
            assertEquals(0, TickDiagnostics.recent().length);
            assertNull(TickDiagnostics.latest());
            TickDiagnostics.beginTick();
            TickDiagnostics.endTick();
            assertEquals(1, TickDiagnostics.latest().tick());
            assertEquals(0, TickDiagnostics.latest().installedCubes());
            assertEquals(2, callbacks.get());
        } finally {
            TickDiagnostics.setObserver(previous);
            TickDiagnostics.reset();
        }
    }

    @Test public void aLifecycleResetCannotDiscardAnActiveTick() {
        AtomicLong clock = new AtomicLong();
        TickDiagnostics.Recorder recorder = new TickDiagnostics.Recorder(clock::get);
        recorder.beginTick();
        try {
            recorder.reset();
            fail("Reset discarded an active tick");
        } catch (IllegalStateException expected) {
            assertEquals("Cannot reset inside a measured tick", expected.getMessage());
        }
        clock.set(5);
        assertEquals(5, recorder.endTick().elapsedNanos());
        recorder.reset();
        recorder.beginTick();
        assertEquals(1, recorder.endTick().tick());
    }

    private long cost(TickDiagnostics.Sample sample, TickDiagnostics.Phase phase) {
        return sample.phaseNanos()[phase.ordinal()];
    }
}
