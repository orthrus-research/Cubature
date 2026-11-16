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

/**
 * Receives world and cube events from the cube source, on the client thread, once per client tick,
 * in the order the changes were applied, after the data they describe is readable.
 */
public interface ICubeSourceListener {

    /** Blocks changed. */
    int BLOCKS = 1;
    /** Block light or sky light changed. */
    int LIGHT = 2;
    /** Biomes changed. */
    int BIOMES = 4;
    /** Nothing in this cube changed; a cube touching it changed at their shared boundary, arrived or left. */
    int NEIGHBOR = 8;

    /** A cubic client world appeared. Cubes already in it are read with {@code forEachLoaded}. */
    void worldAttached(long worldIdentity);

    /** The client world was replaced or closed. Everything held for that identity is void. */
    void worldDetached(long worldIdentity);

    /**
     * The attached world's terrain preview became available or was withdrawn; read it with
     * {@code terrainPreview()}. A preview present when the world attaches raises no event.
     */
    default void terrainPreviewChanged() {
    }

    /**
     * The box of cubes the server keeps sent to this client changed: its center moved to another
     * cube, or its view distances changed. Read it with {@code heldBox()}. Delivered at the end of
     * the tick, after the cube events of that tick. A box already stated when the world attaches
     * raises no event.
     */
    default void heldBoxChanged() {
    }

    /** A cube's data arrived, including a full resend of a cube already held. */
    void cubeLoaded(int cubeX, int cubeY, int cubeZ);

    /** A cube left the client. */
    void cubeUnloaded(int cubeX, int cubeY, int cubeZ);

    /**
     * Something a snapshot captures changed. Several changes to one cube within a tick arrive as one
     * call, their kinds combined and their bounds united.
     *
     * <p>The bounds are a contract: they always contain every block that changed, and may contain
     * more. A change to one block carries that block; merged changes carry the box around them all;
     * a {@link #NEIGHBOR} change carries the whole cube; a render update over a range carries the
     * part of the range inside the cube, and one too wide to follow carries the whole cube for every
     * loaded cube. An arrival ({@link #cubeLoaded}) covers the whole cube, and a change merged into
     * an arrival queued in the same tick raises no call of its own.
     *
     * @param kinds a combination of {@link #BLOCKS}, {@link #LIGHT}, {@link #BIOMES} and {@link #NEIGHBOR}
     * @param minX the bounds of the change within the cube, 0 to 15, inclusive at both ends
     */
    void cubeChanged(int cubeX, int cubeY, int cubeZ, int kinds, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ);
}
