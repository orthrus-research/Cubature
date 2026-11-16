/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.api.client;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import javax.annotation.Nullable;

/**
 * What Cubic Chunks supplies to a client-side renderer that builds its geometry from cubes. Every
 * method is called on the client thread. A renderer reads cubes only through this interface and the
 * snapshots it returns.
 */
public interface ICubeSource {

    /** In {@link #surfaceHeights}: no ground is known, or no liquid stands above the ground. */
    int NO_SURFACE = Integer.MIN_VALUE;

    /** {@link #cubeContent}: the cube is not loaded on the client. */
    int NOT_LOADED = 0;
    /** {@link #cubeContent}: the cube is loaded and holds nothing but air. */
    int EMPTY = 1;
    /** {@link #cubeContent}: the cube is loaded and may hold blocks. */
    int HAS_BLOCKS = 2;

    /**
     * Sets the one listener that receives world and cube events, or removes it with null. A listener
     * set while a world is attached receives no events for what is already loaded; it reads that
     * with {@link #forEachLoaded}.
     */
    void setListener(@Nullable ICubeSourceListener listener);

    /** Whether a cubic client world is attached. */
    boolean attached();

    /**
     * A number that changes whenever the client world is replaced. Snapshots and versions belong to
     * one identity and mean nothing after it.
     */
    long worldIdentity();

    /** The lowest cube Y of the attached world, inclusive. */
    int minCubeY();

    /** The highest cube Y of the attached world, exclusive. */
    int maxCubeY();

    /** Whether the attached world's dimension has sky light. */
    boolean hasSkyLight();

    /** Whether the cube is loaded on the client. */
    boolean isLoaded(int cubeX, int cubeY, int cubeZ);

    /**
     * The cube's version, its generation: a number that increases whenever anything a snapshot would
     * capture of it changes, and with every arrival of its data. Versions are drawn from one counter
     * for the whole world, so a cube that leaves and arrives again never repeats an earlier version.
     * A change only at a neighbor's boundary ({@link ICubeSourceListener#NEIGHBOR}) does not move it.
     * It may move when nothing a snapshot shows differs. -1 for a cube that is not loaded.
     */
    long version(int cubeX, int cubeY, int cubeZ);

    /**
     * The version at which the cube's blocks or biomes last changed or its data arrived: what a mesh
     * is built from, apart from light. It never exceeds {@link #version}, and {@link #version} moves
     * whenever it does. A block edit moves both this and {@link #lightVersion}, since light may follow
     * it. -1 for a cube that is not loaded. The default is {@link #version}.
     */
    default long blockVersion(int cubeX, int cubeY, int cubeZ) {
        return version(cubeX, cubeY, cubeZ);
    }

    /**
     * The version at which the cube's block light or sky light last changed or its data arrived. A
     * cube whose {@link #blockVersion} is unmoved since a mesh was built needs only its light redone.
     * It never exceeds {@link #version}, and {@link #version} moves whenever it does. -1 for a cube
     * that is not loaded. The default is {@link #version}.
     */
    default long lightVersion(int cubeX, int cubeY, int cubeZ) {
        return version(cubeX, cubeY, cubeZ);
    }

    /**
     * A diagnostic: a hash of the block light and sky light of every block of a loaded cube, as the
     * cube answers them, so that two runs or the client and the server can be compared. Never
     * negative for a loaded cube; -1 for a cube that is not loaded, and from a source that does not
     * compute it. It reads 4,096 blocks; it is not for every frame.
     */
    default long lightChecksum(int cubeX, int cubeY, int cubeZ) {
        return -1;
    }

    /** Visits every loaded cube, in no particular order. */
    void forEachLoaded(CubeVisitor visitor);

    /**
     * An immutable copy of the cube and the 26 cubes around it, as they stand now. Neither the
     * center cube nor any neighbor need be loaded, so a cube at the edge of what the client holds is
     * snapshotted like any other: {@link ICubeSnapshot#present} says which cubes it holds, and an
     * absent one reads as air with implicit sky light.
     */
    ICubeSnapshot snapshot(int cubeX, int cubeY, int cubeZ);

    /**
     * The attached world's terrain preview on this client, or null: no world is attached, the
     * generator offers none, the server withholds its seed, the generator's factory declined, or
     * the preview is not built yet. In single player it is the integrated server's own generator.
     * It belongs to one world identity; once the host withdraws it, it and its samplers throw
     * {@link io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException}.
     * Samplers may run on any thread.
     */
    @Nullable
    ICubicTerrainPreview terrainPreview();

    /**
     * The ground and the liquid surface of the 256 block columns of a chunk column, at index
     * {@code localZ << 4 | localX}. Ground is the highest block that blocks movement and is not
     * liquid, a log or plant matter (leaves, plants, vines, cactus, gourds): under a tree it is the
     * soil, and at an overhang it is the overhang's top. The liquid surface is the highest liquid
     * block above that ground. A height is the Y of the block's top face, the block's Y plus one.
     *
     * <p>A block column whose surface the loaded cubes hold is answered from them, so edits are
     * followed, and {@code fromLoaded} is true for it. Any other is answered from the
     * {@linkplain #terrainPreview terrain preview}, as the generator would make it, with
     * {@code fromLoaded} false; with no preview its ground is {@link #NO_SURFACE}. A liquid height
     * of {@link #NO_SURFACE} also means that no liquid stands above the ground.
     *
     * <p>Client thread only. A source that does not track surfaces fills in {@link #NO_SURFACE}
     * and returns 0.
     *
     * @param ground     receives 256 ground heights
     * @param liquid     receives 256 liquid surface heights
     * @param fromLoaded receives, for each block column, whether its heights are the loaded cubes'
     * @return how many of the 256 block columns were answered from loaded cubes
     */
    default int surfaceHeights(int columnX, int columnZ, int[] ground, int[] liquid, boolean[] fromLoaded) {
        return surfaceHeights(columnX, columnZ, ground, liquid, fromLoaded, true);
    }

    /**
     * {@link #surfaceHeights(int, int, int[], int[], boolean[])}, with the choice of leaving the
     * preview unsampled. With {@code allowPreview} false no column of the preview is sampled, so
     * the call costs only its search of loaded cubes: a block column they cannot answer gets a
     * preview answer kept from an earlier call if there is one, and {@link #NO_SURFACE} otherwise,
     * with {@code fromLoaded} false either way.
     */
    default int surfaceHeights(int columnX, int columnZ, int[] ground, int[] liquid, boolean[] fromLoaded,
            boolean allowPreview) {
        java.util.Arrays.fill(ground, 0, 256, NO_SURFACE);
        java.util.Arrays.fill(liquid, 0, 256, NO_SURFACE);
        java.util.Arrays.fill(fromLoaded, 0, 256, false);
        return 0;
    }

    /**
     * What a cube holds, without a snapshot: {@link #NOT_LOADED}, {@link #EMPTY} or
     * {@link #HAS_BLOCKS}. {@link #EMPTY} is given only for a loaded cube whose every block is air.
     * {@link #HAS_BLOCKS} is given for any other loaded cube. It reads the count of blocks the cube
     * keeps, so it costs no more than {@link #isLoaded}. A source that does not know answers
     * {@link #NOT_LOADED}.
     */
    default int cubeContent(int cubeX, int cubeY, int cubeZ) {
        return NOT_LOADED;
    }

    /**
     * A number for a chunk column that changes whenever what its loaded cubes say of
     * {@link #surfaceHeights} could have changed: a cube of the column loaded or unloaded, a block
     * in one changed, or the column's height index was corrected by the server. While it stands,
     * the heights answered from loaded cubes stand. It says nothing of the preview's answers, which
     * change only when the preview does. -1 when no world is attached or surfaces are not tracked.
     */
    default long surfaceVersion(int columnX, int columnZ) {
        return -1;
    }

    /**
     * Cubic Chunks' threads for sampling the {@linkplain #terrainPreview terrain preview}, so that a
     * renderer starts none of its own; null from a source that has none. The same object for the
     * life of the client: its tasks follow whichever preview is attached.
     */
    @Nullable
    default IClientPreviewWork previewWork() {
        return null;
    }

    /** Receives one cube position. */
    @FunctionalInterface
    interface CubeVisitor {
        void visit(int cubeX, int cubeY, int cubeZ);
    }
}
