package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.world.chunk.Chunk;

/**
 * Incremental maintenance: the cube and column indexes with their autosave-discovery and GC cursors.
 * A provider holds one exactly when its delivery mode inspects incrementally, so the cursors never
 * exist without the indexes they walk.
 */
final class MaintenanceState {
    final MaintenanceIndex<Cube> cubes = new MaintenanceIndex<>();
    final MaintenanceIndex<Chunk> columns = new MaintenanceIndex<>();
    /** Autosave discovery restarts as soon as it finishes (deliveryMode SAVE and later). */
    final boolean continuousDiscovery;
    int discoverCubes, discoverColumns, gcCubes, gcColumns;
    boolean discoveryColumnFirst, gcColumnFirst;

    MaintenanceState(boolean continuousDiscovery) { this.continuousDiscovery = continuousDiscovery; }
}
