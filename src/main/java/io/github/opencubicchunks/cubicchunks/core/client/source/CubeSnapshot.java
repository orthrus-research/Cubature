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
package io.github.opencubicchunks.cubicchunks.core.client.source;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

import javax.annotation.Nullable;

/** A cube and its 26 neighbors, as immutable copies. */
final class CubeSnapshot implements ICubeSnapshot {

    private static final IBlockState AIR = Blocks.AIR.getDefaultState();

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final long worldIdentity;
    private final boolean hasSkyLight;
    private final WorldType worldType;
    /** By {@code (dx + 1) * 9 + (dy + 1) * 3 + dz + 1}. */
    private final CubeCopy[] cubes;

    CubeSnapshot(int centerX, int centerY, int centerZ, long worldIdentity, boolean hasSkyLight,
            WorldType worldType, CubeCopy[] cubes) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.worldIdentity = worldIdentity;
        this.hasSkyLight = hasSkyLight;
        this.worldType = worldType;
        this.cubes = cubes;
    }

    @Override
    public int centerX() {
        return centerX;
    }

    @Override
    public int centerY() {
        return centerY;
    }

    @Override
    public int centerZ() {
        return centerZ;
    }

    @Override
    public long worldIdentity() {
        return worldIdentity;
    }

    private CubeCopy at(int dx, int dy, int dz) {
        return cubes[(dx + 1) * 9 + (dy + 1) * 3 + dz + 1];
    }

    @Override
    public boolean present(int dx, int dy, int dz) {
        return at(dx, dy, dz).loaded;
    }

    @Override
    public long version(int dx, int dy, int dz) {
        return at(dx, dy, dz).version;
    }

    @Override
    public boolean centerEmpty() {
        return at(0, 0, 0).empty;
    }

    @Override
    public void release() {
        // Copies belong to their cubes and pin nothing.
    }

    /** The copy holding a block, or null outside the neighborhood. */
    @Nullable
    private CubeCopy cubeOf(int x, int y, int z) {
        int dx = (x >> 4) - centerX;
        int dy = (y >> 4) - centerY;
        int dz = (z >> 4) - centerZ;
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
            return null;
        }
        return at(dx, dy, dz);
    }

    private IBlockState state(int x, int y, int z) {
        CubeCopy cube = cubeOf(x, y, z);
        return cube == null || cube.blocks == null ? AIR : cube.blocks[(y & 15) << 8 | (z & 15) << 4 | (x & 15)];
    }

    private int light(EnumSkyBlock type, int x, int y, int z) {
        CubeCopy cube = cubeOf(x, y, z);
        if (type == EnumSkyBlock.SKY) {
            if (!hasSkyLight) {
                return 0;
            }
            return cube == null ? 15 : cube.skyLight(x & 15, y & 15, z & 15);
        }
        return cube == null ? 0 : cube.blockLight(x & 15, y & 15, z & 15);
    }

    /** As the game's chunk cache: a block that uses its neighbors' brightness takes the brightest of them. */
    private int lightWithNeighbors(EnumSkyBlock type, BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (!state(x, y, z).useNeighborBrightness()) {
            return light(type, x, y, z);
        }
        int brightest = 0;
        for (EnumFacing facing : EnumFacing.VALUES) {
            int value = light(type, x + facing.getXOffset(), y + facing.getYOffset(), z + facing.getZOffset());
            if (value >= 15) {
                return 15;
            }
            brightest = Math.max(brightest, value);
        }
        return brightest;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return state(pos.getX(), pos.getY(), pos.getZ());
    }

    @Nullable
    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        CubeCopy cube = cubeOf(pos.getX(), pos.getY(), pos.getZ());
        return cube == null ? null : cube.blockEntities.get(pos);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        int sky = lightWithNeighbors(EnumSkyBlock.SKY, pos);
        int block = Math.max(lightWithNeighbors(EnumSkyBlock.BLOCK, pos), lightValue);
        return sky << 20 | block << 4;
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        IBlockState state = getBlockState(pos);
        return state.getBlock().isAir(state, this, pos);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        CubeCopy cube = cubeOf(pos.getX(), pos.getY(), pos.getZ());
        int id = cube == null ? -1 : cube.biome(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        Biome biome = id < 0 || id == 255 ? null : Biome.getBiome(id);
        return biome == null ? Biomes.PLAINS : biome;
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return getBlockState(pos).getStrongPower(this, pos, direction);
    }

    @Override
    public WorldType getWorldType() {
        return worldType;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean ifOutside) {
        if (cubeOf(pos.getX(), pos.getY(), pos.getZ()) == null) {
            return ifOutside;
        }
        return getBlockState(pos).isSideSolid(this, pos, side);
    }
}
