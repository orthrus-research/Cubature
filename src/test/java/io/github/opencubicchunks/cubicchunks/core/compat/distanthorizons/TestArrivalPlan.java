package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestArrivalPlan {
    private static final long TICK = 50_000_000L, SECOND = 1_000_000_000L;
    /** A 12-cube view: its square reaches 200 blocks from the player. */
    private static final double HALF = 200;

    /** A plan whose player has flown +x at {@code speed} blocks/s for two seconds and is now at x = 0. */
    private static ArrivalPlan flying(double speed, double nativeShare) {
        ArrivalPlan plan = new ArrivalPlan();
        for (int i = -40; i <= 0; i++) plan.observe(100 * SECOND + i * TICK, speed * i * TICK / 1e9, 0, HALF, nativeShare);
        return plan;
    }

    @Test public void coverTimeFollowsTheTravel() {
        ArrivalPlan.State still = new ArrivalPlan.State(0, 0, 0, 0, HALF, true);
        assertEquals(0, ArrivalPlan.coverSeconds(still, 0, 0, 64), 0);
        assertEquals(Double.POSITIVE_INFINITY, ArrivalPlan.coverSeconds(still, 300, 0, 64), 0);
        ArrivalPlan.State flying = new ArrivalPlan.State(0, 0, 34, 0, HALF, true);
        // The section [300, 364) is inside once x + 200 >= 364: after 164 / 34 s.
        assertEquals(164 / 34.0, ArrivalPlan.coverSeconds(flying, 300, 0, 64), 1e-9);
        assertEquals("Behind and receding", Double.POSITIVE_INFINITY, ArrivalPlan.coverSeconds(flying, -300, 0, 64), 0);
        assertEquals("Wider than the view", Double.POSITIVE_INFINITY, ArrivalPlan.coverSeconds(flying, 300, 0, 512), 0);
        assertEquals("Off to the side", Double.POSITIVE_INFINITY, ArrivalPlan.coverSeconds(flying, 300, 300, 64), 0);
    }

    @Test public void sectionsTheNativeViewWillCoverFirstAreHeld() {
        ArrivalPlan plan = flying(34, 1);
        assertEquals("Covered now", ArrivalPlan.HOLD, plan.key(6, 0, 0, 64));
        // Two seconds of smoothing put the velocity at about 33.5 blocks/s.
        assertEquals("Covered in about 4.9 s, before its data (5 s)", ArrivalPlan.HOLD, plan.key(6, 300, 0, 64));
        assertNotEquals("Covered in about 10.9 s, after", ArrivalPlan.HOLD, plan.key(6, 500, 0, 64));
        assertNotEquals("Beside the path", ArrivalPlan.HOLD, plan.key(6, 64, 260, 64));
    }

    @Test public void aLaggingNativeViewHoldsNothingWhileMoving() {
        ArrivalPlan plan = flying(34, 0.8);
        assertNotEquals(ArrivalPlan.HOLD, plan.key(6, 300, 0, 64));
        assertNotEquals("Its corners may need LOD", ArrivalPlan.HOLD, plan.key(6, 0, 0, 64));
        ArrivalPlan still = flying(0, 0.8);
        assertEquals("At a join the native view fills in", ArrivalPlan.HOLD, still.key(6, 0, 0, 64));
    }

    @Test public void coarseDetailGoesFirstAndAheadBeforeBehind() {
        ArrivalPlan plan = flying(34, 1);
        long fineAhead = plan.key(7, 900, 0, 128), coarseFar = plan.key(9, 2500, 0, 512);
        assertTrue(ArrivalPlan.coarse(coarseFar));
        assertFalse(ArrivalPlan.coarse(fineAhead));
        assertTrue("4-block and coarser first", coarseFar < fineAhead);
        assertTrue("Ahead before behind", plan.key(8, 1000, 0, 256) < plan.key(8, -1256, 0, 256));
        assertTrue("Nearer ahead first", plan.key(8, 1000, 0, 256) < plan.key(8, 2000, 0, 256));
    }

    @Test public void lateCoarseArrivalsStepCoarserAndOnTimeSpareOnesStepFiner() {
        ArrivalPlan plan = flying(34, 1);
        long now = 100 * SECOND;
        // 4-block sections arriving 500 blocks out: inside the 768-block ring where 2-block is wanted.
        for (int i = 0; i < 10; i++) plan.arrived(i, now + i, 8, 500, 0, 256);
        assertEquals(9, plan.floor());
        // 8-block sections arriving 2,000 blocks out are on time; with few coarse sends there is room for finer.
        long later = now + 20 * SECOND;
        for (int i = 0; i < 10; i++) plan.sent(i < 3);
        for (int i = 0; i < 10; i++) plan.arrived(100 + i, later + i, 9, 2000, 0, 512);
        assertEquals(8, plan.floor());
    }

    @Test public void serviceTimeRunsFromQueueingToTheAnswer() {
        ArrivalPlan plan = flying(34, 1);
        plan.queued(7, 100 * SECOND);
        plan.arrived(7, 110 * SECOND, 6, 5000, 5000, 64);
        assertEquals(5 + (10 - 5) * 0.2, plan.serviceSeconds(6), 1e-9);
    }

    @Test public void inFlightRequestsCoverAboutThreeSecondsOfAnswers() {
        ArrivalPlan plan = new ArrivalPlan();
        assertEquals("Never fewer than 8", 8, plan.inflight(40));
        long t = 0;
        for (int second = 0; second < 30; second++) {
            for (int i = 0; i < 10; i++) plan.arrived(second * 100 + i, t, 6, 5000, 5000, 64);
            t += SECOND;
            plan.observe(t, 0, 0, HALF, 1);
        }
        assertEquals(10, plan.answerRate(), 0.1);
        assertEquals("10 a second for 3 s", 30, plan.inflight(40));
        assertEquals("DH's limit", 20, plan.inflight(20));
        for (int second = 0; second < 30; second++) plan.observe(t += SECOND, 0, 0, HALF, 1);
        assertEquals("No answers: back to 8", 8, plan.inflight(40));
    }

    @Test public void aTeleportIsNotTravel() {
        ArrivalPlan plan = flying(34, 1);
        plan.observe(100 * SECOND + TICK, 5000, 5000, HALF, 1);
        assertEquals(0, plan.speed(), 0);
    }
}
