/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.api.worldgen;

import javax.annotation.Nullable;

/**
 * Optional, non-loading summaries of a pure cube generator's output. Queries use only immutable
 * data already held by the generator: they do not generate, load, prepare or wait for missing data,
 * and do not access a live world. Implementations must be thread-safe.
 *
 * <p>Cache eviction may make a summary unknown, but cannot invalidate an earlier empty result.
 * Callers still load saved state and perform normal population and cube lifecycle work.
 */
public interface ICubeClassification {
    /** What normal generation would produce at one cube position. */
    enum CubeClass {
        /**
         * Generation into a fresh primer produces only Minecraft air blocks and sets no 3D biome
         * entries. The bound includes every generation-time feature that could reach this cube,
         * including features rooted in neighbouring columns.
         */
        EMPTY,

        /** Ordinary generation is required; this does not promise that any block is non-air. */
        DETAILED,

        /** Required cached data is absent or insufficient to classify the cube. */
        UNKNOWN
    }

    /**
     * Classifies a cube without causing work on a cache miss. Coordinates are cube coordinates,
     * each spanning 16 blocks. Returns {@link CubeClass#UNKNOWN} when required summaries are missing,
     * never null.
     */
    CubeClass cubeClass(int cubeX, int cubeY, int cubeZ);

    /**
     * Whether generating this cube and populating it create nothing: the cube is
     * {@link CubeClass#EMPTY}, and its population places no block, biome, entity or scheduled work
     * anywhere, whatever the world's state. A host may then leave such a cube unsaved while nothing
     * has changed it, and make it again by generation, population included. Like
     * {@link #cubeClass}, it never builds data; false when unsure.
     */
    default boolean regenerable(int cubeX, int cubeY, int cubeZ) {
        return false;
    }

    /**
     * A conservative surface band for one chunk column, or null when required cached data is
     * missing. Coordinates are chunk-column coordinates. The band covers the column's surface
     * ground, overlying fluids and generation-time surface decorations, including features rooted
     * in neighbouring columns; it may include extra cubes. Querying it does not build a column.
     */
    @Nullable SurfaceBand surfaceBand(int columnX, int columnZ);

    /** Inclusive cube-Y bounds of a column's surface band, in 16-block cube units. */
    record SurfaceBand(int minimumCubeY, int maximumCubeY) {
        public SurfaceBand {
            if (minimumCubeY > maximumCubeY)
                throw new IllegalArgumentException("Surface band bounds are reversed");
        }
    }
}
