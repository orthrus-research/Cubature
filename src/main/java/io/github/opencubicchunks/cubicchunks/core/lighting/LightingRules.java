package io.github.opencubicchunks.cubicchunks.core.lighting;

/** Shared light rules for unallocated sections and deferred cube-face updates. */
public final class LightingRules {
    private LightingRules() {}

    public static int emptySkyLight(boolean hasSkyLight, int topOpaqueY, int blockY) {
        return hasSkyLight && blockY > topOpaqueY ? 15 : 0;
    }

    /** IN requests belong to the waiting cube, on its face toward the neighbor. */
    public static int incomingFaceOffset(int direction) {
        return direction > 0 ? 15 : 0;
    }
}
