package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

/**
 * A preview generator that can hold DH's admission of new tiles. While it holds, MixinDistantHorizonsNativeFirst
 * reports DH's world generator busy: tiles already running finish and none starts.
 */
public interface PreviewAdmission {
    boolean holdsNewTiles();
}
