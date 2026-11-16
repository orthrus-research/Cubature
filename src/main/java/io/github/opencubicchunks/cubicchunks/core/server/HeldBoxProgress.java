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
package io.github.opencubicchunks.cubicchunks.core.server;

import java.util.Arrays;

import javax.annotation.Nullable;

/**
 * How far the server has come in sending one player's held box: the cubes within its horizontal
 * view distance of the player's cube along X and Z and its vertical one along Y. The box is walked
 * in a fixed order from a cursor that only moves past cubes already sent, a bounded number of
 * cubes a tick, so a box of 25 cubes a side costs a few ticks of look-ups once and then one look-up
 * a tick while it waits on a cube. Reset whenever the box changes.
 */
final class HeldBoxProgress {

    /** Whether the cube at a position has been sent to the player. */
    interface Sent {
        boolean test(int cubeX, int cubeY, int cubeZ);
    }

    @Nullable private int[] box;
    private long cursor;
    private boolean done;

    /** Starts over for a new box: {centerX, centerY, centerZ, horizontal, vertical}. */
    void reset(int centerX, int centerY, int centerZ, int horizontal, int vertical) {
        int[] next = {centerX, centerY, centerZ, horizontal, vertical};
        if (Arrays.equals(next, box)) {
            return;
        }
        box = next;
        cursor = 0;
        done = false;
    }

    /** The box being walked, or null before the first. */
    @Nullable
    int[] box() {
        return box == null ? null : box.clone();
    }

    boolean done() {
        return done;
    }

    /** The cubes in the box. */
    static long size(int horizontal, int vertical) {
        long side = 2L * horizontal + 1;
        return side * side * (2L * vertical + 1);
    }

    /**
     * Moves the cursor past cubes already sent, looking at no more than {@code budget} of them.
     *
     * @return true only on the call that finds the whole box sent
     */
    boolean advance(Sent sent, int budget) {
        if (box == null || done) {
            return false;
        }
        int horizontal = box[3];
        int vertical = box[4];
        long side = 2L * horizontal + 1;
        long height = 2L * vertical + 1;
        long size = side * side * height;
        for (int looked = 0; looked < budget && cursor < size; looked++) {
            // Bottom layer first, then upward: the order cubes near the player's feet usually arrive in does not matter.
            long layer = cursor / (side * side);
            long inLayer = cursor % (side * side);
            int x = box[0] - horizontal + (int) (inLayer % side);
            int z = box[2] - horizontal + (int) (inLayer / side);
            int y = box[1] - vertical + (int) layer;
            if (!sent.test(x, y, z)) {
                return false;
            }
            cursor++;
        }
        if (cursor < size) {
            return false;
        }
        done = true;
        return true;
    }
}
