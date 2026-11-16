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

import net.minecraft.world.IBlockAccess;

/**
 * An immutable copy of a cube and the 26 cubes around it. It may be read from any thread for as long
 * as it is held, and presents the game's block access interface for every block in that neighborhood,
 * so block models and color handlers run against it unchanged. Blocks outside the neighborhood, and
 * cubes that were not loaded, read as air with sky light as an empty cube would have it.
 *
 * <p>Block entities are the live objects, as in the game's own chunk cache.
 */
public interface ICubeSnapshot extends IBlockAccess {

    int centerX();

    int centerY();

    int centerZ();

    /** The world identity the snapshot was taken in. */
    long worldIdentity();

    /** Whether the cube at this offset from the center, each -1 to 1, was loaded. */
    boolean present(int dx, int dy, int dz);

    /**
     * The {@linkplain ICubeSource#blockVersion block version} of the cube at this offset when the
     * snapshot was taken, or -1 if it was not loaded. The default is {@link #version}.
     */
    default long blockVersion(int dx, int dy, int dz) {
        return version(dx, dy, dz);
    }

    /**
     * The {@linkplain ICubeSource#lightVersion light version} of the cube at this offset when the
     * snapshot was taken, or -1 if it was not loaded. The default is {@link #version}.
     */
    default long lightVersion(int dx, int dy, int dz) {
        return version(dx, dy, dz);
    }

    /**
     * The light of one type at a block, 0 to 15, as stored: unlike {@link #getCombinedLight}, a block
     * that takes its neighbors' brightness (a slab, stairs) is not given theirs. Outside the
     * neighborhood, and in a cube the snapshot does not hold, it reads as {@link #getCombinedLight}
     * does there: no block light and, in a dimension with sky light, the sky light of an empty cube.
     */
    default int getLightFor(net.minecraft.world.EnumSkyBlock type, net.minecraft.util.math.BlockPos pos) {
        int combined = getCombinedLight(pos, 0);
        return type == net.minecraft.world.EnumSkyBlock.SKY ? combined >> 20 & 15 : combined >> 4 & 15;
    }

    /** The version of the cube at this offset when the snapshot was taken, or -1 if it was not loaded. */
    long version(int dx, int dy, int dz);

    /** Whether the center cube holds no blocks at all, so nothing in it needs to be read. */
    boolean centerEmpty();

    /** Gives the snapshot up. Holding a snapshot never keeps a cube loaded. */
    void release();
}
