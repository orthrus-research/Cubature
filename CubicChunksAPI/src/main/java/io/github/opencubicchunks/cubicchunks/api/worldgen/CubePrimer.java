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
package io.github.opencubicchunks.cubicchunks.api.worldgen;

import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.biome.Biome;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Arrays;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class CubePrimer {
    public static final IBlockState DEFAULT_STATE = Blocks.AIR.getDefaultState();

    private final char[] data;
    private byte[] extData = null; // NEID-compat
    private Biome[] biomes3d = null;

    public boolean hasBiomes() {
        return biomes3d != null;
    }

    public CubePrimer() {
        this(new char[4096]);
    }

    public static CubePrimer createFilled(IBlockState state) {
        @SuppressWarnings("deprecation")
        int value = Block.BLOCK_STATE_IDS.get(state);
        char lsb = (char) value;
        char[] data = new char[4096];
        Arrays.fill(data, lsb);
        return new CubePrimer(data);
    }

    protected CubePrimer(char[] data) {
        this.data = data;
    }
    /**
     * Returns biome in a given 4x4x4 block section.
     *
     * @param localBiomeX cube-local X coordinate. One unit is 4 blocks
     * @param localBiomeY cube-local Y coordinate. One unit is 4 blocks
     * @param localBiomeZ cube-local Z coordinate. One unit is 4 blocks
     * @return currently set biome at the given position in this cube. Null if no biome has been set.
     */
    @SuppressWarnings("unused")
    @Nullable
    public Biome getBiome(int localBiomeX, int localBiomeY, int localBiomeZ) {
        if (biomes3d == null) {
            return null;
        }
        return this.biomes3d[biomeIndex(localBiomeX, localBiomeY, localBiomeZ)];
    }

    /**
     * Sets biome in a given 4x4x4 block section.
     * <p>
     * Cubature stores one biome per 4x4x4 section, as cubes do. Upstream stored 2x16x2-block columns, so
     * every section of a cube took the biome last set at its X and Z.
     *
     * @param localBiomeX cube-local X coordinate. One unit is 4 blocks
     * @param localBiomeY cube-local Y coordinate. One unit is 4 blocks
     * @param localBiomeZ cube-local Z coordinate. One unit is 4 blocks
     * @param biome biome to set in this cube position. After this method returns, {@link #getBiome(int, int, int)}
     *              returns this biome for the same supplied input coordinates.
     */
    @SuppressWarnings("unused")
    public void setBiome(int localBiomeX, int localBiomeY, int localBiomeZ, Biome biome) {
        if (this.biomes3d == null) {
            this.biomes3d = new Biome[4 * 4 * 4];
        }
        this.biomes3d[biomeIndex(localBiomeX, localBiomeY, localBiomeZ)] = biome;
    }

    private static int biomeIndex(int localBiomeX, int localBiomeY, int localBiomeZ) {
        return localBiomeX | localBiomeY << 2 | localBiomeZ << 4;
    }

    /**
     * Get the block state at the given location
     *
     * @param x cube local x
     * @param y cube local y
     * @param z cube local z
     * @return the block state
     */
    public IBlockState getBlockState(int x, int y, int z) {
        int idx = getBlockIndex(x, y, z);
        int block = this.data[idx];
        if (extData != null) {
            block |= extData[idx] << 16;
        }
        @SuppressWarnings("deprecation")
        IBlockState iblockstate = Block.BLOCK_STATE_IDS.getByValue(block);
        return iblockstate == null ? DEFAULT_STATE : iblockstate;
    }

    /**
     * Set the block state at the given location
     *
     * @param x     cube local x
     * @param y     cube local y
     * @param z     cube local z
     * @param state the block state
     */
    public void setBlockState(int x, int y, int z, @Nonnull IBlockState state) {
        @SuppressWarnings("deprecation")
        int value = Block.BLOCK_STATE_IDS.get(state);
        char lsb = (char) value;
        int idx = getBlockIndex(x, y, z);
        this.data[idx] = lsb;
        if (value > 0xFFFF) {
            if (extData == null) {
                extData = new byte[4096];
            }
            extData[idx] = (byte) (value >>> 16);
        }
    }

    /**
     * Resets this primer to a state as if it were newly constructed.
     */
    public void reset() {
        Arrays.fill(this.data, '\0');

        // simply reset extra data and biomes to null, these are unlikely to be set in most cases anyway
        this.extData = null;
        this.biomes3d = null;
    }

    /**
     * Map cube local coordinates to an array index in the range [0, 4095].
     *
     * @param x cube local x
     * @param y cube local y
     * @param z cube local z
     * @return a unique array index for that coordinate
     */
    private static int getBlockIndex(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }
}
