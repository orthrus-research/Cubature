package io.github.opencubicchunks.cubicchunks.lab;
import org.junit.Test;
import static org.junit.Assert.*;
public class TestLabTourViews {
    /** Ticks at one position, a frame drawn after each; the view taken, or null after {@code ticks}. */
    private static LabTourViews.View hold(LabTourViews views, int ticks, double x, double y, double z, boolean settled, boolean idle) {
        for (int i = 0; i < ticks; i++) {
            LabTourViews.View view = views.tick(x, y, z, settled);
            if (view != null) return view;
            views.frame(idle);
        }
        return null;
    }
    @Test public void aSettledViewIsTakenOnceThePlayerHeldStillLongEnough() {
        LabTourViews views = new LabTourViews(120, 1200, 10);
        LabTourViews.View view = hold(views, 2000, 0, 100, 0, true, true);
        assertNotNull(view);
        assertEquals(0, view.index);
        assertTrue(view.settled);
        assertEquals(120, view.stillTicks);
        assertNull("one view per position", hold(views, 2000, 0, 100, 0, true, true));
    }
    @Test public void aStepOfAClimbShorterThanTheStillTicksIsNoView() {
        LabTourViews views = new LabTourViews(120, 1200, 10);
        assertNull(hold(views, 100, 0, 200, 0, true, true));  // lab.run's 5 s on each step of a descent
        assertNull(hold(views, 100, 0, 168, 0, true, true));
        assertEquals(0, hold(views, 2000, 0, 136, 0, true, true).index);
    }
    @Test public void aViewWaitsForTheCubeSourceAndTheRenderer() {
        LabTourViews views = new LabTourViews(120, 1200, 10);
        assertNull("cubes still arriving", hold(views, 500, 0, 100, 0, false, true));
        views.frame(false);
        assertNull("renderer still busy", hold(views, 500, 0, 100, 0, true, false));
        for (int i = 0; i < 9; i++) views.frame(true);
        assertNull("nine idle frames are not ten", views.tick(0, 100, 0, true));
        views.frame(true);
        LabTourViews.View view = views.tick(0, 100, 0, true);
        assertNotNull(view);
        assertTrue(view.settled);
    }
    @Test public void aViewThatNeverSettlesIsTakenAtTheLimitAndSaysSo() {
        LabTourViews views = new LabTourViews(120, 1200, 10);
        LabTourViews.View view = hold(views, 5000, 0, 100, 0, false, true);
        assertFalse(view.settled);
        assertEquals(1200, view.stillTicks);
    }
    @Test public void aNewViewIsMoreThanEightBlocksFromTheLastOneTaken() {
        LabTourViews views = new LabTourViews(120, 1200, 10);
        assertEquals(0, hold(views, 2000, 0, 100, 0, true, true).index);
        assertNull("4 + 4 blocks is the same view", hold(views, 2000, 4, 100, 4, true, true));
        assertEquals(1, hold(views, 2000, 9, 100, 0, true, true).index);
        assertEquals(2, hold(views, 2000, 9, 91, 0, true, true).index);
    }
    @Test public void framesBeforeAMoveDoNotCountForTheViewAfterIt() {
        LabTourViews views = new LabTourViews(120, 1200, 10);
        assertNull(hold(views, 50, 0, 100, 0, true, true));
        assertNull(views.tick(20, 100, 0, true));
        for (int i = 0; i < 119; i++) assertNull(views.tick(20, 100, 0, true));
        assertNull("no frame drawn since the move", views.tick(20, 100, 0, true));
    }
    @Test(expected = IllegalArgumentException.class) public void aLimitBelowTheStillTicksIsRefused() { new LabTourViews(120, 60, 10); }
}
