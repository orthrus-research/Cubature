package io.github.opencubicchunks.cubicchunks.core.server;



/**
 * What cube delivery asks of the provider: which cubes it still holds and how far the native view is behind. The player
 * cube map implements it, and the provider reads it through the world's player chunk map, so the
 * provider never depends on the map's class (TestLayerBoundary).
 */
public interface CubeDemand {
    /**
     * Whether delivery still needs a cube loaded although it may hold no ticket yet: a watcher covers
     * it (its load callback may still be pending) or it lies within a player's delivery view.
     */
    boolean holdsCube(int cubeX, int cubeY, int cubeZ);

    /**
     * Whether more than one face of the native view, {@code (2 x view + 1)^2} cubes, waits to generate or to be sent:
     * a join or a teleport. Native delivery comes first, so meanwhile neither regional preparation nor a Distant
     * Horizons preview tile starts. Updated each tick; other threads may read it.
     */
    boolean nativeBehind();

    /**
     * While the native view is behind, each player's view, four ints a player: its block x and z, and the view's
     * bottom block Y and exclusive top. Otherwise empty. Updated each tick; other threads may read it.
     */
    int[] nativeViews();
}
