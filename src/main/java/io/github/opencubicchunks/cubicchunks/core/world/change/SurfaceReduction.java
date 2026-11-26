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
package io.github.opencubicchunks.cubicchunks.core.world.change;

/**
 * A cube's level-0 surface: for each of its 256 block columns, whether the column's top opaque block lies in the cube,
 * and if so its height in the cube, its map color and its kind. Two reductions that are equal draw the same from far
 * away; this is what "edited" is judged by, against the baseline a cube keeps from generation.
 *
 * <p>Plants, snow layers and other blocks of no opacity are not the top: the column's opacity height map skips them,
 * so a crop growing or snow falling does not change the reduction. Neither does a grass block turning to dirt, which
 * keeps its kind and differs only in map color when the color differs.
 *
 * <p>An entry is an int: 0 when the column's top is not in the cube, otherwise bit 31 set, the kind in bits 12 to 13,
 * the map color index in bits 4 to 9 and the height in the cube in bits 0 to 3. Columns are indexed {@code z * 16 + x}.
 */
public final class SurfaceReduction {

    public static final int COLUMNS = 256;
    public static final int ABSENT = 0;
    public static final int KIND_SOLID = 0;
    public static final int KIND_LIQUID = 1;

    private static final int PRESENT = 1 << 31;

    /** Where a column's top opaque block is, in absolute Y; anything below the cube means "not here". */
    @FunctionalInterface
    public interface ColumnTops {
        int topY(int localX, int localZ);
    }

    /** What the block at the top looks like from far away. */
    public interface TopCells {
        int colorIndex(int localX, int localY, int localZ);

        int kind(int localX, int localY, int localZ);
    }

    private SurfaceReduction() {
    }

    public static int index(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    public static int entry(int localY, int colorIndex, int kind) {
        return PRESENT | ((kind & 3) << 12) | ((colorIndex & 63) << 4) | (localY & 15);
    }

    public static boolean isPresent(int entry) {
        return (entry & PRESENT) != 0;
    }

    public static int localY(int entry) {
        return entry & 15;
    }

    public static int colorIndex(int entry) {
        return (entry >>> 4) & 63;
    }

    public static int kind(int entry) {
        return (entry >>> 12) & 3;
    }

    /** Reduces the cube whose lowest block is at {@code cubeMinY}. */
    public static int[] reduce(int cubeMinY, ColumnTops tops, TopCells cells) {
        int[] reduction = new int[COLUMNS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int localY = tops.topY(x, z) - cubeMinY;
                if (localY >= 0 && localY < 16) {
                    reduction[index(x, z)] = entry(localY, cells.colorIndex(x, localY, z), cells.kind(x, localY, z));
                }
            }
        }
        return reduction;
    }

    /** Whether no column's top is in the cube. */
    public static boolean isEmpty(int[] reduction) {
        for (int entry : reduction) {
            if (entry != ABSENT) {
                return false;
            }
        }
        return true;
    }

    /** A 64-bit FNV-1a hash of the entries; equal reductions hash equal. */
    public static long hash(int[] reduction) {
        long h = 0xcbf29ce484222325L;
        for (int entry : reduction) {
            for (int shift = 0; shift < 32; shift += 8) {
                h ^= (entry >>> shift) & 0xFF;
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    /**
     * The columns whose entries differ, as a 256-bit set: column {@code i} is bit {@code i & 63} of word {@code i >> 6}.
     */
    public static long[] differing(int[] now, int[] baseline) {
        long[] bits = new long[4];
        for (int i = 0; i < COLUMNS; i++) {
            if (now[i] != baseline[i]) {
                bits[i >> 6] |= 1L << (i & 63);
            }
        }
        return bits;
    }

    public static int count(long[] columnBits) {
        int n = 0;
        for (long word : columnBits) {
            n += Long.bitCount(word);
        }
        return n;
    }
}
