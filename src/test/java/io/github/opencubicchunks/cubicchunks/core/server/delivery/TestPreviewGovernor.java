package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class TestPreviewGovernor {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static long[] ticks(int count, double ms) {
        long[] t = new long[count];
        Arrays.fill(t, (long) (ms * 1e6));
        return t;
    }

    @Test public void startsAtHalfTheCeiling() {
        assertEquals(4, new PreviewGovernor(8).permits());
        assertEquals(1, new PreviewGovernor(1).permits());
    }

    @Test public void aSlowTailHalvesThePermitsDownToOne() {
        PreviewGovernor governor = new PreviewGovernor(8);
        long[] slow = ticks(40, 20);
        slow[39] = slow[38] = (long) 48e6;
        assertEquals(2, governor.update(SECOND, slow, true, true));
        assertEquals(1, governor.update(2 * SECOND, slow, true, true));
        assertEquals("Never below one", 1, governor.update(3 * SECOND, slow, true, true));
    }

    @Test public void slowTicksWhileNoTileRanCutNothing() {
        // A join: the native view's own work slows the ticks while the gate holds every tile.
        PreviewGovernor governor = new PreviewGovernor(8);
        long[] join = ticks(40, 400);
        assertEquals(4, governor.update(SECOND, join, false, false));
        assertEquals(4, governor.update(2 * SECOND, join, false, false));
    }

    @Test public void aCalmTailAddsOnePermitEverySettledSecondWhileTilesWait() {
        PreviewGovernor governor = new PreviewGovernor(4);
        long[] calm = ticks(40, 30);
        assertEquals(3, governor.update(SECOND, calm, true, true));
        assertEquals("Within the settling time", 3, governor.update(2 * SECOND, calm, true, true));
        assertEquals(4, governor.update(3 * SECOND, calm, true, true));
        assertEquals("At the ceiling", 4, governor.update(6 * SECOND, calm, true, true));
    }

    @Test public void noRaiseWithoutWaitingTilesOrBetweenTheThresholds() {
        PreviewGovernor governor = new PreviewGovernor(8);
        assertEquals("Nothing waits", 4, governor.update(SECOND, ticks(40, 30), false, true));
        // Of 40 ticks the p95 is the third slowest.
        long[] band = ticks(40, 30);
        band[39] = band[38] = band[37] = (long) 42e6;
        assertEquals("p95 between 40 and 45 ms holds", 4, governor.update(5 * SECOND, band, true, true));
        assertEquals("Too few ticks to judge a raise", 4, governor.update(9 * SECOND, ticks(10, 10), true, true));
    }

    @Test public void theP95IsTheSecondSlowestOfTwentyTicks() {
        long[] t = ticks(20, 10);
        t[3] = (long) 90e6;
        t[7] = (long) 50e6;
        assertEquals(50, PreviewGovernor.p95Ms(t, 20), 1e-9);
        assertEquals(0, PreviewGovernor.p95Ms(new long[0], 20), 1e-9);
    }
}
