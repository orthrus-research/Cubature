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

import javax.annotation.Nullable;

/**
 * What Cubic Chunks supplies to a client-side renderer that builds its geometry from cubes. Every
 * method is called on the client thread. A renderer reads cubes only through this interface and the
 * snapshots it returns.
 */
public interface ICubeSource {

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
     * The cube's version: a number that increases whenever anything a snapshot would capture of it
     * changes. -1 for a cube that is not loaded.
     */
    long version(int cubeX, int cubeY, int cubeZ);

    /** Visits every loaded cube, in no particular order. */
    void forEachLoaded(CubeVisitor visitor);

    /**
     * An immutable copy of the cube and the 26 cubes around it, as they stand now. The center cube
     * need not be loaded.
     */
    ICubeSnapshot snapshot(int cubeX, int cubeY, int cubeZ);

    /** Receives one cube position. */
    @FunctionalInterface
    interface CubeVisitor {
        void visit(int cubeX, int cubeY, int cubeZ);
    }
}
