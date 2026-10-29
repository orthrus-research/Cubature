package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;

import java.util.Iterator;

/**
 * What cube delivery asks of the provider: which cubes to tick and which it still holds. The player
 * cube map implements it, and the provider reads it through the world's player chunk map, so the
 * provider never depends on the map's class (TestLayerBoundary).
 */
public interface CubeDemand {
    /** The cubes the provider ticks: those watchers tick and those in forced chunks. */
    Iterator<Cube> getCubeIterator();

    /**
     * Whether delivery still needs a cube loaded although it may hold no ticket yet: a watcher covers
     * it (its load callback may still be pending) or it lies within a player's delivery view.
     */
    boolean holdsCube(int cubeX, int cubeY, int cubeZ);
}
