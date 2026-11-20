/*
 *  This file is part of Cubature, licensed under the MIT License (MIT).
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
package io.github.opencubicchunks.cubicchunks.core.world;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * A cube column's 16 blocks given to the height map at once leave it as the 16 calls a block at a time, top down,
 * leave it: the same heights and segments, compared as the map saves them, over random columns and random runs,
 * the runs chosen so the shortcuts and the per-block path are both met often.
 */
public class TestServerHeightMapRuns {

    private static final int[] MASKS_OFTEN = {0, 0xFFFF};

    @Test public void runsLeaveTheMapAsBlocksAtATimeDo() {
        Random random = new Random(8675309);
        int applied = 0;
        for (int round = 0; round < 4000; round++) {
            ServerHeightMap runs = new ServerHeightMap(new int[256]);
            // A random column history: blocks set and cleared over a few cubes' height, some whole cubes at a time.
            int x = random.nextInt(16);
            int z = random.nextInt(16);
            int base = (random.nextInt(9) - 4) * 16;
            int history = random.nextInt(60);
            for (int i = 0; i < history; i++) {
                if (random.nextInt(5) == 0) {
                    int bottom = base + (random.nextInt(6) - 3) * 16;
                    int mask = random.nextBoolean() ? MASKS_OFTEN[random.nextInt(2)] : random.nextInt(1 << 16);
                    for (int k = 15; k >= 0; k--) {
                        runs.onOpacityChange(x, bottom + k, z, mask >> k & 1);
                    }
                } else {
                    runs.onOpacityChange(x, base + random.nextInt(96) - 48, z, random.nextInt(3) == 0 ? 0 : 255);
                }
            }
            ServerHeightMap blocks = runs.copy();
            assertArrayEquals("a copy saves as its original", runs.getData(), blocks.getData());
            // Then one cube's column, both ways, a few times over.
            for (int step = 0; step < 4; step++) {
                int bottom = base + (random.nextInt(8) - 4) * 16;
                int mask = random.nextInt(4) != 0 ? MASKS_OFTEN[random.nextInt(2)] : random.nextInt(1 << 16);
                runs.onOpacityRun(x, bottom, z, mask);
                for (int k = 15; k >= 0; k--) {
                    blocks.onOpacityChange(x, bottom + k, z, mask >> k & 1);
                }
                assertArrayEquals("round " + round + " step " + step + ": run at " + bottom + " of " + Integer.toHexString(mask),
                        blocks.getData(), runs.getData());
                applied++;
            }
        }
        assertTrue(applied == 16000);
    }

    // The shortcuts one by one, from a column that already holds a range.
    @Test public void eachShortcutLeavesTheMapAsBlocksAtATimeDo() {
        int[][] cases = {
                // range bottom, range top, run bottom, mask
                {Integer.MIN_VALUE, 0, 0, 0xFFFF},   // no blocks yet, all opaque
                {-32, 31, 0, 0xFFFF},                // all opaque inside the range
                {16, 40, 0, 0xFFFF},                 // all opaque ending where the range begins
                {-32, -17, 0, 0},                    // all open above the range
                {32, 63, 0, 0},                      // all open below the range
                {-32, 31, 0, 0},                     // all open inside the range: the per-block path
                {32, 63, 0, 0xFFFF},                 // all opaque below with a gap: the per-block path
                {-32, -1, 0, 0xFFFF},                // all opaque above the range: the per-block path
        };
        for (int[] c : cases) {
            ServerHeightMap runs = new ServerHeightMap(new int[256]);
            if (c[0] != Integer.MIN_VALUE) {
                for (int y = c[1]; y >= c[0]; y--) {
                    runs.onOpacityChange(3, y, 5, 255);
                }
            }
            ServerHeightMap blocks = runs.copy();
            runs.onOpacityRun(3, c[2], 5, c[3]);
            for (int k = 15; k >= 0; k--) {
                blocks.onOpacityChange(3, c[2] + k, 5, c[3] >> k & 1);
            }
            assertArrayEquals("range " + c[0] + " to " + c[1] + ", run at " + c[2] + " of " + Integer.toHexString(c[3]),
                    blocks.getData(), runs.getData());
        }
    }
}
