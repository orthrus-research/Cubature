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

    /** In {@link #loadedNeighbors}: the bit of the cube itself. */
    int NEIGHBOR_CENTER = 1 << 13;
    /** In {@link #loadedNeighbors}: the bits of the six cubes that share a face with it. */
    int NEIGHBOR_FACES = 1 << 4 | 1 << 10 | 1 << 12 | 1 << 14 | 1 << 16 | 1 << 22;
    /** In {@link #loadedNeighbors}: the bits of all 26 cubes around it. */
    int NEIGHBOR_ALL = (1 << 27) - 1 & ~NEIGHBOR_CENTER;

    /**
     * The highest number of block states a cubic world keeps: cube saves store 20 bits of a state's
     * id, so a state whose id is this or more would lose its high bits when saved.
     */
    int MAX_BLOCK_STATES = 1 << 20;

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

    /**
     * The bit for the cube at an offset in {@link #loadedNeighbors}, each offset -1 to 1: bit
     * {@code (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)}, the order {@link ICubeSnapshot#present} uses.
     */
    static int neighborBit(int dx, int dy, int dz) {
        return 1 << ((dx + 1) * 9 + (dy + 1) * 3 + dz + 1);
    }

    /**
     * Which of a cube and the 26 around it are loaded, without a snapshot: a mask of
     * {@link #neighborBit} bits, {@link #NEIGHBOR_CENTER} for the cube itself. {@code
     * Integer.bitCount(mask & NEIGHBOR_ALL)} counts the loaded neighbors, and {@code (mask &
     * NEIGHBOR_FACES) == NEIGHBOR_FACES} says all six faces have one. Client thread. The default
     * reads {@link #isLoaded} 27 times.
     */
    default int loadedNeighbors(int cubeX, int cubeY, int cubeZ) {
        int mask = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (isLoaded(cubeX + dx, cubeY + dy, cubeZ + dz)) {
                        mask |= neighborBit(dx, dy, dz);
                    }
                }
            }
        }
        return mask;
    }

    /**
     * The light at one block, without a snapshot: sky light in bits 4 to 7 and block light in bits 0
     * to 3 ({@code sky << 4 | block}), as stored, or -1 when the block's cube is not loaded. A cube
     * without block storage answers its implicit sky light. Client thread. The default answers -1.
     */
    default int light(int blockX, int blockY, int blockZ) {
        return -1;
    }

    /**
     * The cube alone, without the 26 around it: an {@link ICubeSnapshot} whose neighbors are all
     * absent ({@link ICubeSnapshot#present} false), so they read as air with no block light and, in a
     * dimension with sky light, full sky light, whatever is loaded there. For a reader of the center
     * only, such as a mesher of interior faces: it copies only the center. The default takes a full
     * {@link #snapshot}.
     */
    default ICubeSnapshot snapshotCenter(int cubeX, int cubeY, int cubeZ) {
        return snapshot(cubeX, cubeY, cubeZ);
    }

    /**
     * The highest number of block states this world keeps, {@link #MAX_BLOCK_STATES}. Snapshots hold
     * block states themselves, not their ids, so nothing read through the source has a narrower
     * limit.
     */
    default int maxBlockStates() {
        return MAX_BLOCK_STATES;
    }

    /**
     * The box of cubes the server keeps sent to this client, as the server last stated it: {@code
     * {centerX, centerY, centerZ, horizontal, vertical}}, a new array each call, or null when the
     * server has not stated one (no world attached, or a server without this message). The box is
     * every cube within {@code horizontal} of the center along X and Z and within {@code vertical}
     * along Y, each inclusive. The center is the player's cube as the server last moved it, which
     * can trail the player by a tick or two. The client may also hold cubes outside the box (sent
     * ahead of the player, or not yet unloaded); every cube inside it is one the server will send.
     * Client thread. The default answers null.
     */
    @Nullable
    default int[] heldBox() {
        return null;
    }

    /**
     * Whether a cube lies in the {@linkplain #heldBox held box}; false when there is none. Client
     * thread.
     */
    default boolean inHeldBox(int cubeX, int cubeY, int cubeZ) {
        int[] box = heldBox();
        return box != null && Math.abs((long) cubeX - box[0]) <= box[3] && Math.abs((long) cubeY - box[1]) <= box[4]
                && Math.abs((long) cubeZ - box[2]) <= box[3];
    }

    /**
     * How many client ticks in a row, ending with the last one, nothing in the view moved: no cube
     * arrived, left or changed (blocks, light or biomes, a neighbor's included), the held box did not
     * change, and no client light work was left queued at the tick's end. 0 in a tick with any of
     * those, and with no world attached. Client thread. The default answers 0.
     */
    default int quietTicks() {
        return 0;
    }

    /**
     * Whether the server has said it sent every cube of the current {@linkplain #heldBox held box}.
     * The server says so once per box, after the cubes' own messages, so when this turns true every
     * cube of the box has arrived. It turns false again when the box changes (the player moves to
     * another cube, or a view distance changes), until the server says so of the new box. False with
     * no box. Client thread. The default answers false.
     */
    default boolean heldBoxSent() {
        return false;
    }

    /**
     * Whether the view is settled (docs/CUBE-SOURCE.md, section 6): every cube of the held box has
     * arrived ({@link #heldBoxSent}) and the view has been quiet for at least {@code quietTicks}
     * ticks. From a server that states no held box, the quiet ticks alone. It says nothing of light
     * the server computes after it sent a cube, of what a renderer has built, or of entities. Client
     * thread.
     *
     * @param quietTicks how many quiet ticks to require; 20 (one second) suits frame captures
     */
    default boolean settled(int quietTicks) {
        return quietTicks() >= quietTicks && (heldBox() == null || heldBoxSent());
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
     * How many cube copies snapshots have made since the client started. A cube is copied when a
     * snapshot needs it and its version has moved since its last copy; every other snapshot of it
     * shares that copy. Client thread.
     */
    default long cubeCopies() {
        return 0;
    }

    /** The client thread's time making those copies since the client started, in nanoseconds. Client thread. */
    default long cubeCopyNanos() {
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

    /**
     * Cubic Chunks' threads for work on cube snapshots, such as building meshes in the background,
     * so that a renderer starts none of its own; null from a source that has none. The same object for
     * the life of the client; its tasks belong to the attached world. It shares its budget of running
     * jobs with {@link #previewWork()}.
     */
    @Nullable
    default IClientBuildWork buildWork() {
        return null;
    }

    /** Receives one cube position. */
    @FunctionalInterface
    interface CubeVisitor {
        void visit(int cubeX, int cubeY, int cubeZ);
    }
}
