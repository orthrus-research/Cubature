package io.github.opencubicchunks.cubicchunks.core.lighting;

/**
 * Told when lighting changes a column's surface height. On a server the player cube map implements
 * it so column watchers resend heights; lighting reads it through the world's player chunk map and
 * never depends on the map's class (TestLayerBoundary).
 */
@FunctionalInterface
public interface SurfaceHeightListener {
    void heightUpdated(int blockX, int blockZ);
}
