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

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.core.lighting.LightingRules;
import io.github.opencubicchunks.cubicchunks.core.world.IColumnInternal;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * An immutable copy of everything a snapshot holds of one cube, made on the client thread. One copy
 * serves every snapshot that includes the cube until the cube's version changes, so a cube is copied
 * once however many of its neighbors are built.
 */
final class CubeCopy {

    private static final int OPEN = 0;
    private static final int DARK = 1;
    private static final int BY_TOPS = 2;

    final long version;
    final long blockVersion;
    final long lightVersion;
    final boolean loaded;
    /** Block states by {@code y << 8 | z << 4 | x}, or null for a cube without block storage. */
    @Nullable final IBlockState[] blocks;
    /** Whether the cube holds nothing but air. */
    final boolean empty;
    @Nullable private final byte[] blockLight;
    @Nullable private final byte[] skyLight;
    /** For a cube without storage: how its implicit sky light reads. */
    private final int implicitSky;
    /** The column's highest opaque block by {@code z << 4 | x}, kept only when the cube straddles them. */
    @Nullable private final int[] tops;
    private final int minBlockY;
    /** The cube's own biomes, 4 by 4 by 4, or null when it has none. */
    @Nullable private final byte[] cubeBiomes;
    /** The column's biomes, 16 by 16, used when the cube has none of its own. */
    @Nullable private final byte[] columnBiomes;
    final Map<BlockPos, TileEntity> blockEntities;
    /** The client tick this copy was last handed to a snapshot, for {@link KeptCopies}. Client thread. */
    long asked;

    private CubeCopy(long version, long blockVersion, long lightVersion, boolean loaded,
            @Nullable IBlockState[] blocks, boolean empty,
            @Nullable byte[] blockLight, @Nullable byte[] skyLight, int implicitSky, @Nullable int[] tops,
            int minBlockY, @Nullable byte[] cubeBiomes, @Nullable byte[] columnBiomes,
            Map<BlockPos, TileEntity> blockEntities) {
        this.version = version;
        this.blockVersion = blockVersion;
        this.lightVersion = lightVersion;
        this.loaded = loaded;
        this.blocks = blocks;
        this.empty = empty;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.implicitSky = implicitSky;
        this.tops = tops;
        this.minBlockY = minBlockY;
        this.cubeBiomes = cubeBiomes;
        this.columnBiomes = columnBiomes;
        this.blockEntities = blockEntities;
    }

    /** A cube that is not loaded, in no loaded column: air, open to the sky. */
    static final CubeCopy NOTHING = new CubeCopy(-1, -1, -1, false, null, true, null, null, OPEN, null, 0, null, null,
            Collections.emptyMap());

    /** The copy for a loaded cube, reused while the cube's version stands. */
    static CubeCopy of(Cube cube, boolean hasSkyLight) {
        Object held = cube.sourceCopy;
        if (held instanceof CubeCopy && ((CubeCopy) held).version == cube.sourceVersion) {
            return (CubeCopy) held;
        }
        CubeCopy copy = copy(cube, hasSkyLight);
        cube.sourceCopy = copy;
        return copy;
    }

    private static CubeCopy copy(Cube cube, boolean hasSkyLight) {
        Chunk column = cube.getColumn();
        byte[] cubeBiomes = cube.getBiomeArray();
        cubeBiomes = cubeBiomes == null ? null : cubeBiomes.clone();
        byte[] columnBiomes = cubeBiomes == null ? column.getBiomeArray().clone() : null;
        Map<BlockPos, TileEntity> entities = cube.getTileEntityMap().isEmpty() ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(cube.getTileEntityMap()));
        int minBlockY = Coords.cubeToMinBlock(cube.getY());
        ExtendedBlockStorage storage = cube.getStorage();
        if (storage == null) {
            int[] tops = hasSkyLight ? tops(column) : null;
            return new CubeCopy(cube.sourceVersion, cube.sourceBlockVersion, cube.sourceLightVersion, true, null,
                    true, null, null, implicit(tops, minBlockY),
                    tops, minBlockY, cubeBiomes, columnBiomes, entities);
        }
        IBlockState[] blocks = new IBlockState[4096];
        BlockStateContainer data = storage.getData();
        int index = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    blocks[index++] = data.get(x, y, z);
                }
            }
        }
        NibbleArray sky = storage.getSkyLight();
        return new CubeCopy(cube.sourceVersion, cube.sourceBlockVersion, cube.sourceLightVersion, true, blocks,
                storage.isEmpty(),
                storage.getBlockLight().getData().clone(), sky == null ? null : sky.getData().clone(), OPEN, null,
                minBlockY, cubeBiomes, columnBiomes, entities);
    }

    /** A cube that is not loaded in a column that is: air, with the sky light an empty cube would have. */
    static CubeCopy absent(Chunk column, int cubeY, boolean hasSkyLight) {
        int minBlockY = Coords.cubeToMinBlock(cubeY);
        int[] tops = hasSkyLight ? tops(column) : null;
        return new CubeCopy(-1, -1, -1, false, null, true, null, null, implicit(tops, minBlockY), tops, minBlockY,
                null,
                column.getBiomeArray().clone(), Collections.emptyMap());
    }

    /** For tests: a loaded cube with storage, its biomes and block entities left empty. */
    static CubeCopy stored(long version, long blockVersion, long lightVersion, IBlockState[] blocks,
            byte[] blockLight, @Nullable byte[] skyLight) {
        return new CubeCopy(version, blockVersion, lightVersion, true, blocks, false, blockLight, skyLight, OPEN, null,
                0, null, null, Collections.emptyMap());
    }

    private static int[] tops(Chunk column) {
        int[] tops = new int[256];
        IColumnInternal internal = (IColumnInternal) column;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                tops[z << 4 | x] = internal.getTopYWithStaging(x, z);
            }
        }
        return tops;
    }

    private static int implicit(@Nullable int[] tops, int minBlockY) {
        if (tops == null) {
            return DARK;
        }
        boolean open = true;
        boolean covered = true;
        for (int top : tops) {
            open &= top < minBlockY;
            covered &= top >= minBlockY + 15;
        }
        return open ? OPEN : covered ? DARK : BY_TOPS;
    }

    /** Block light at local coordinates, each 0 to 15. */
    int blockLight(int x, int y, int z) {
        return blockLight == null ? 0 : nibble(blockLight, x, y, z);
    }

    /** Sky light at local coordinates; the caller has checked that the dimension has sky light. */
    int skyLight(int x, int y, int z) {
        if (blocks != null) {
            return skyLight == null ? 0 : nibble(skyLight, x, y, z);
        }
        switch (implicitSky) {
            case OPEN:
                return 15;
            case DARK:
                return 0;
            default:
                return LightingRules.emptySkyLight(true, tops[z << 4 | x], minBlockY + y);
        }
    }

    private static int nibble(byte[] data, int x, int y, int z) {
        int index = y << 8 | z << 4 | x;
        int packed = data[index >> 1];
        return (index & 1) == 0 ? packed & 15 : packed >> 4 & 15;
    }

    /** The biome id at local coordinates, 0 to 255, or -1 where none is stored. */
    int biome(int x, int y, int z) {
        if (cubeBiomes != null) {
            return cubeBiomes[(x >> 2) << 4 | (y >> 2) << 2 | z >> 2] & 255;
        }
        return columnBiomes == null ? -1 : columnBiomes[z << 4 | x] & 255;
    }

    /** Whether this copy still equals the cube it was made from; used by the diagnostic. */
    @Nullable
    String difference(Cube cube, boolean hasSkyLight) {
        ExtendedBlockStorage storage = cube.getStorage();
        if ((storage == null) != (blocks == null)) {
            return "storage " + (storage == null ? "removed" : "added");
        }
        if (storage == null) {
            return null;
        }
        BlockStateContainer data = storage.getData();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (blocks[y << 8 | z << 4 | x] != data.get(x, y, z)) {
                        return "block at " + x + " " + y + " " + z;
                    }
                    if (blockLight(x, y, z) != storage.getBlockLight(x, y, z)) {
                        return "block light at " + x + " " + y + " " + z;
                    }
                    if (hasSkyLight && skyLight(x, y, z) != storage.getSkyLight(x, y, z)) {
                        return "sky light at " + x + " " + y + " " + z;
                    }
                }
            }
        }
        return null;
    }
}
