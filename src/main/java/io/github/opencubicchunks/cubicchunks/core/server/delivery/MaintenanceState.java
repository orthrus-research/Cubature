package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import javax.annotation.Nullable;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.world.chunk.Chunk;

/**
 * Incremental maintenance: the cube and column indexes with their autosave-discovery and GC cursors.
 * A provider holds one exactly when its delivery mode inspects incrementally, so the cursors never
 * exist without the indexes they walk.
 */
public final class MaintenanceState {
    final MaintenanceIndex<Cube> cubes = new MaintenanceIndex<>();
    final MaintenanceIndex<Chunk> columns = new MaintenanceIndex<>();
    /** Autosave discovery restarts as soon as it finishes (deliveryMode SAVE and later). */
    final boolean continuousDiscovery;
    int discoverCubes, discoverColumns, gcCubes, gcColumns;
    boolean discoveryColumnFirst, gcColumnFirst;

    public MaintenanceState(boolean continuousDiscovery) { this.continuousDiscovery = continuousDiscovery; }

    /** The loaded cubes the cursors walk. */
    public MaintenanceIndex<Cube> cubes() { return cubes; }

    /** The loaded columns the cursors walk. */
    public MaintenanceIndex<Chunk> columns() { return columns; }

    /** Starts a GC pass over every indexed cube and column, unless one is under way. */
    public void beginGcPass() {
        if (gcCubes == 0 && gcColumns == 0) {
            gcCubes = cubes.size();
            gcColumns = columns.size();
        }
    }

    /** Whether the GC pass has cubes or columns left to inspect. */
    public boolean gcPending() { return gcCubes > 0 || gcColumns > 0; }

    /** Whether the GC pass's next inspection is a column; turns alternate while both have entries left. */
    public boolean gcColumnTurn() {
        gcColumnFirst = !gcColumnFirst;
        return gcColumns > 0 && (gcColumnFirst || gcCubes == 0);
    }

    /** The GC pass's next column, or null when the index ran out, which ends the pass's columns. */
    @Nullable public Chunk nextGcColumn() {
        gcColumns--;
        Chunk column = columns.next(false);
        if (column == null) gcColumns = 0;
        return column;
    }

    /** The GC pass's next cube, or null when the index ran out, which ends the pass's cubes. */
    @Nullable public Cube nextGcCube() {
        gcCubes--;
        Cube cube = cubes.next(false);
        if (cube == null) gcCubes = 0;
        return cube;
    }
}
