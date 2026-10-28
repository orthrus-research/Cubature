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

import java.util.HashMap;
import java.util.Map;

/** Synthetic hash distribution only; this does not measure a native Minecraft workload. */
public final class QueuedCubeHashDiagnostic {
    private QueuedCubeHashDiagnostic() {
    }

    public static void main(String[] args) {
        System.out.println("workload,implementation,keys,distinct_hashes,largest_equal_hash_group,buckets,occupied_buckets,largest_bucket");
        int[][] vertical = new int[16384][3];
        int[][] horizontal = new int[16384][3];
        int[][] plane = new int[16384][3];
        int[][] volume = new int[32768][3];
        for (int i = 0; i < 16384; i++) {
            vertical[i] = new int[] {8, i - 8192, 0};
            horizontal[i] = new int[] {8, 0, i - 8192};
            plane[i] = new int[] {i / 128 - 64, 0, i % 128 - 64};
        }
        for (int i = 0; i < 32768; i++) {
            volume[i] = new int[] {i / 1024 - 16, i / 32 % 32 - 16, i % 32 - 16};
        }
        report("vertical_z_zero", vertical);
        report("horizontal_y_zero", horizontal);
        report("origin_plane", plane);
        report("volume_32", volume);
    }

    private static void report(String name, int[][] positions) {
        // Use a fixed synthetic table of 32,768 slots for comparable bucket distributions.
        // Keep the world contribution at zero to isolate coordinate mixing in both versions.
        for (boolean previous : new boolean[] {true, false}) {
            Map<Integer, Integer> hashes = new HashMap<>();
            int[] buckets = new int[32768];
            int largestHashGroup = 0, largestBucket = 0, occupiedBuckets = 0;
            for (int[] position : positions) {
                int x = position[0], y = position[1], z = position[2];
                int hash = previous ? (x * 31 + y * 23 * z * 29) : QueuedCube.hashCoordinates(x, y, z);
                int count = hashes.getOrDefault(hash, 0) + 1;
                hashes.put(hash, count);
                largestHashGroup = Math.max(largestHashGroup, count);
                int bucket = (hash ^ (hash >>> 16)) & (buckets.length - 1);
                if (buckets[bucket] == 0) {
                    occupiedBuckets++;
                }
                largestBucket = Math.max(largestBucket, ++buckets[bucket]);
            }
            System.out.printf("%s,%s,%d,%d,%d,%d,%d,%d%n", name, previous ? "upstream" : "coordinate_mix",
                    positions.length, hashes.size(), largestHashGroup, buckets.length, occupiedBuckets, largestBucket);
        }
    }
}
