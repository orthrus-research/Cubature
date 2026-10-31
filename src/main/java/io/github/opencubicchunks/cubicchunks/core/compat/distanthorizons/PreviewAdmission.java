package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

/**
 * A preview generator that takes part in DH's admission of new tiles (MixinDistantHorizonsNativeFirst): DH's world
 * generation queue finds the generator busy while it holds new tiles or, when Cubature's preview pool samples them,
 * while the pool admits no more.
 */
public interface PreviewAdmission {
    /** Whether no new tile may start: the world's native view is behind. */
    boolean holdsNewTiles();

    /** Whether Cubature's preview pool samples the tiles, so its permits decide admission instead of DH's thread count. */
    boolean pooled();

    /** With the pool: whether another tile may start while {@code inProgress} are in progress. */
    boolean admits(int inProgress);
}
