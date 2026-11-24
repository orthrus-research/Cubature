package io.github.opencubicchunks.cubicchunks.lab;

/**
 * When the tour client takes a view, apart from the game so the rule is tested without one.
 * <p>
 * Workbench's lab.run moves the player to each view of a tour with the server's {@code tp} (position, yaw and pitch)
 * and then waits for the client's capture line, so the client never reads the tour: a view is a position more than
 * 8 blocks from the last one taken (sideways, or up and down), as Riven's tour client counts one. It is taken once the
 * player has held still for {@code stillTicks}, longer than the pause lab.run makes on each step of a long climb or
 * descent (100 ticks), so a step is never taken for a view, and once the view has settled: the cube source says every
 * cube of the server's held box arrived and nothing in it changed for the quiet ticks asked
 * ({@code ICubeSource.settled}), and the game's renderer has had no chunk update left for {@code renderFrames} frames
 * in a row. A view that has not settled after {@code limitTicks} of holding still is taken anyway and says so.
 */
final class LabTourViews {
    /** How far, in blocks, a position must be from the last view taken to be a new view. */
    static final double NEW_VIEW = 8;
    /** How far, in blocks, the player may move in a tick and still hold still. */
    static final double STILL = .05;

    final int stillTicks, limitTicks, renderFrames;
    private double lastX = Double.NaN, lastY, lastZ, takenX = Double.NaN, takenY, takenZ;
    private int still, idleFrames, taken;

    LabTourViews(int stillTicks, int limitTicks, int renderFrames) {
        if (stillTicks < 1 || limitTicks < stillTicks || renderFrames < 0)
            throw new IllegalArgumentException("still " + stillTicks + ", limit " + limitTicks + ", frames " + renderFrames);
        this.stillTicks = stillTicks;
        this.limitTicks = limitTicks;
        this.renderFrames = renderFrames;
    }

    /** One frame drawn: idle when the renderer had no chunk update left after it. */
    void frame(boolean idle) {
        idleFrames = idle ? idleFrames + 1 : 0;
    }

    /**
     * One client tick at the player's position, with whether the cube source says the view has settled. The view to
     * take now, or null.
     */
    View tick(double x, double y, double z, boolean settled) {
        boolean moved = Double.isNaN(lastX) || Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ) > STILL;
        lastX = x;
        lastY = y;
        lastZ = z;
        if (moved) {
            still = 0;
            idleFrames = 0;  // frames drawn before the move say nothing of the view after it
            return null;
        }
        still++;
        boolean newView = Double.isNaN(takenX) || Math.abs(x - takenX) + Math.abs(z - takenZ) > NEW_VIEW
                || Math.abs(y - takenY) > NEW_VIEW;
        if (!newView || still < stillTicks) return null;
        boolean ready = settled && idleFrames >= renderFrames;
        if (!ready && still < limitTicks) return null;
        takenX = x;
        takenY = y;
        takenZ = z;
        return new View(taken++, ready, still, idleFrames);
    }

    /** A view to take: its number from 0, whether it settled, and how long the player held still for it. */
    static final class View {
        final int index;
        final boolean settled;
        final int stillTicks, idleFrames;

        View(int index, boolean settled, int stillTicks, int idleFrames) {
            this.index = index;
            this.settled = settled;
            this.stillTicks = stillTicks;
            this.idleFrames = idleFrames;
        }
    }
}
