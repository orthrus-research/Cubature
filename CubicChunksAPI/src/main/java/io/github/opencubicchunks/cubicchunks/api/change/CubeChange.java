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
package io.github.opencubicchunks.cubicchunks.api.change;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.Set;

/**
 * What changed in one cube during one server tick. A cube written many times in a tick, by a paste of millions of
 * blocks for example, still makes one change.
 */
public final class CubeChange {

    private final CubePos pos;
    private final long version;
    private final long mask;
    private final Set<ResourceLocation> causes;

    /**
     * @param pos     the cube
     * @param version the cube's new edit version, from the server's monotonic counter
     * @param mask    the 4x4x4 sub-blocks written, bit {@link #subBlockIndex}
     * @param causes  the causes of the writes, never empty; the set is kept, not copied
     */
    public CubeChange(CubePos pos, long version, long mask, Set<ResourceLocation> causes) {
        this.pos = Objects.requireNonNull(pos, "pos");
        this.version = version;
        this.mask = mask;
        this.causes = Objects.requireNonNull(causes, "causes");
    }

    /** The bit a block's 4x4x4 sub-block has in {@link #mask()}, from its coordinates within the cube (0 to 15). */
    public static int subBlockIndex(int localX, int localY, int localZ) {
        return ((localY >> 2) << 4) | ((localZ >> 2) << 2) | (localX >> 2);
    }

    public CubePos pos() {
        return pos;
    }

    /**
     * The cube's edit version after this change. Versions only grow: a later change to any cube of the server has a
     * larger one, and a cube saves its latest.
     */
    public long version() {
        return version;
    }

    /** The 4x4x4 sub-blocks written this tick, one bit each; see {@link #subBlockIndex}. Never 0. */
    public long mask() {
        return mask;
    }

    /** Whether the sub-block holding this block was written. */
    public boolean touches(int localX, int localY, int localZ) {
        return (mask & (1L << subBlockIndex(localX, localY, localZ))) != 0;
    }

    /** The causes of this tick's writes to the cube, never empty. Unmodifiable. */
    public Set<ResourceLocation> causes() {
        return causes;
    }

    @Override public String toString() {
        return "CubeChange{" + pos + ", v" + version + ", mask=" + Long.toHexString(mask) + ", " + causes + "}";
    }
}
