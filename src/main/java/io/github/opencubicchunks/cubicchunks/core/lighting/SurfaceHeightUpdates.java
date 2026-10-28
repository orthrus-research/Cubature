package io.github.opencubicchunks.cubicchunks.core.lighting;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;

/** Column notifications preserve the old cube footprint, including its positive X/Z edge. */
final class SurfaceHeightUpdates {
    @FunctionalInterface
    interface Updater { void heightUpdated(int blockX, int blockZ); }

    private SurfaceHeightUpdates() { }

    static void forCube(CubePos position, Updater updater) {
        int minX = position.getMinBlockX(), minZ = position.getMinBlockZ();
        for (int x = minX; x <= position.getMaxBlockX() + 1; x++) {
            for (int z = minZ; z <= position.getMaxBlockZ() + 1; z++) {
                updater.heightUpdated(x, z);
            }
        }
    }
}
