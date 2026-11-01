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

    @Test public void aStillPlayerGetsNearestFirstAndFlyingAgainRestoresTheFlightDetail() {
        ArrivalPlan plan = flying(34, 1);
        long now = 100 * SECOND;
        for (int i = 0; i < 10; i++) plan.arrived(i, now + i, 8, 500, 0, 256);
        assertEquals("Late in flight: 8-block", 9, plan.floor());
        // Stopped at x = 0: the smoothed speed falls under 2 blocks/s in about 1.3 s, then a second of rest.
        for (int i = 1; i <= 60; i++) plan.observe(now + i * TICK, 0, 0, HALF, 1);
        assertEquals("At rest: 1-block", ArrivalPlan.FINEST, plan.floor());
        long nearFine = plan.key(6, 300, 0, 64), farCoarse = plan.key(10, 2500, 0, 1024);
        assertTrue(ArrivalPlan.coarse(nearFine));
        assertTrue("Nearest first", nearFine < farCoarse);
        long later = now + 60 * TICK;
        for (int i = 1; i <= 20; i++) plan.observe(later + i * TICK, 34 * i * TICK / 1e9, 0, HALF, 1);
        assertEquals("Flying again: the in-flight detail", 9, plan.floor());
    }

    /** A plan whose player has moved +x at {@code speed} for 30 s while the server answered {@code perSecond} a second. */
    private static ArrivalPlan answering(double speed, int perSecond) {
        ArrivalPlan plan = new ArrivalPlan();
        long t = 100 * SECOND;
        for (int tick = -600; tick <= 0; tick++) {
            if (tick % 20 == 0)
                for (int i = 0; i < perSecond; i++) plan.arrived(tick * 100L + i, t + tick * TICK, 6, 5000, 5000, 64);
            plan.observe(t + tick * TICK, speed * tick * TICK / 1e9, 0, HALF, 1);
        }
        return plan;
    }

    @Test public void walkingTheOneBlockRingComesAfterCoverageAndBeforeOtherFineSections() {
        // Walking at 4 blocks/s the ring sweeps up 768 * 4 / 4096 = 0.75 sections a second: within half of 2 answers.
        ArrivalPlan plan = answering(4, 2);
        assertEquals(2, plan.answerRate(), 0.1);
        // DH's MEDIUM quality wants 1-block cells up to 192 * 2 = 384 blocks out.
        long near = plan.key(6, 64, 260, 64), coarseFar = plan.key(10, 3000, 0, 1024), fineAhead = plan.key(7, 900, 0, 128);
        assertTrue("Beside the path, 260 blocks out", ArrivalPlan.nearRing(near));
        assertFalse("500 blocks out", ArrivalPlan.nearRing(plan.key(6, 64, 500, 64)));
        assertTrue("Coverage first", coarseFar < near);
        assertTrue("Then the ring, before finer sections ahead", near < fineAhead);
    }

    @Test public void inElytraFlightTheRingWaitsWithTheOtherFineSections() {
        // At 34 blocks/s the ring sweeps up about 6.4 sections a second, more than 2 answers could serve.
        ArrivalPlan plan = answering(34, 2);
        long near = plan.key(6, 64, 260, 64);
        assertFalse(ArrivalPlan.nearRing(near));
        assertFalse(ArrivalPlan.coarse(near));
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
