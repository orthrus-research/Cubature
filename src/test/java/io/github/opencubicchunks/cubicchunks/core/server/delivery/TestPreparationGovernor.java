package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class TestPreparationGovernor {
    private static final long TRICKLE = TimeUnit.SECONDS.toNanos(5);

    @Test public void spareHeadroomAddsOnePermitASecondUpToTheMaximum() {
        PreparationGovernor governor = new PreparationGovernor(4, TRICKLE);
        assertEquals(1, governor.permits());
        for (int second = 0; second < 6; second++) governor.update(.40, .5);
        assertEquals(4, governor.permits());
    }

    @Test public void aSaturatedMachineHalvesThePermits() {
        PreparationGovernor governor = grown(4);
        governor.update(.97, 0);
        assertEquals(2, governor.permits());
        governor.update(.97, 0);
        assertEquals(1, governor.permits());
        governor.update(.97, 0);
        assertEquals(0, governor.permits());
    }

    @Test public void aServerThreadBusyWithItsOwnWorkDoesNotThrottle() {
        // A slow tick with idle cores (the server thread computing, or waiting on I/O) is not contention.
        PreparationGovernor governor = grown(4);
        governor.update(.30, 20);
        assertEquals(4, governor.permits());
    }

    @Test public void aServerThreadKeptOffBusyCoresHalvesThePermits() {
        PreparationGovernor governor = grown(4);
        governor.update(.90, 6);
        assertEquals(2, governor.permits());
    }

    @Test public void withoutACpuReadingTheServerWaitDecides() {
        PreparationGovernor governor = grown(4);
        governor.update(-1, 6);
        assertEquals(2, governor.permits());
        governor.update(-1, 1);
        assertEquals(3, governor.permits());
    }

    @Test public void atZeroPermitsOnePreparationTricklesEachPeriod() {
        PreparationGovernor governor = new PreparationGovernor(4, TRICKLE);
        governor.update(.99, 0);
        assertEquals(0, governor.permits());
        long now = 1_000_000_000L;
        assertTrue(governor.admits(0, now));
        assertFalse("One at a time", governor.admits(1, now));
        assertFalse("Not again within the period", governor.admits(0, now + TRICKLE - 1));
        assertTrue(governor.admits(0, now + TRICKLE));
    }

    private static PreparationGovernor grown(int permits) {
        PreparationGovernor governor = new PreparationGovernor(permits, TRICKLE);
        while (governor.permits() < permits) governor.update(.40, .5);
        return governor;
    }
}
