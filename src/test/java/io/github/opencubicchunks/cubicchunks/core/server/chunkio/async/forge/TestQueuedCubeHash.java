/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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
package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestQueuedCubeHash {
    @Test public void verticalLoadsAtZeroZKeepDistinctHashes() {
        Set<Integer> hashes = new HashSet<>();
        for (int y = -8192; y < 8192; y++) {
            assertTrue("collision at height " + y, hashes.add(QueuedCube.hashCoordinates(8, y, 0)));
        }
    }

    @Test public void horizontalLoadsAtZeroYKeepDistinctHashes() {
        Set<Integer> hashes = new HashSet<>();
        for (int z = -8192; z < 8192; z++) {
            assertTrue("collision at depth " + z, hashes.add(QueuedCube.hashCoordinates(8, 0, z)));
        }
    }

    @Test public void nearbyThreeDimensionalLoadsAvoidCollisionClusters() {
        Set<Integer> hashes = new HashSet<>();
        for (int x = -16; x < 16; x++) {
            for (int y = -16; y < 16; y++) {
                for (int z = -16; z < 16; z++) {
                    hashes.add(QueuedCube.hashCoordinates(x, y, z));
                }
            }
        }
        assertTrue("at least 99% of this local volume should have distinct hashes", hashes.size() >= 32441);
    }

    @Test public void eachAxisRetainsExtremeCoordinates() {
        int[] values = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int fixed : values) {
            Set<Integer> xHashes = new HashSet<>(), yHashes = new HashSet<>(), zHashes = new HashSet<>();
            for (int value : values) {
                xHashes.add(QueuedCube.hashCoordinates(value, fixed, fixed));
                yHashes.add(QueuedCube.hashCoordinates(fixed, value, fixed));
                zHashes.add(QueuedCube.hashCoordinates(fixed, fixed, value));
            }
            assertEquals(values.length, xHashes.size());
            assertEquals(values.length, yHashes.size());
            assertEquals(values.length, zHashes.size());
        }
    }
}
