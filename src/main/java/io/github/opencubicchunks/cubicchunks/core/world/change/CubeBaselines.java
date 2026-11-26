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

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.IHeightMap;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import javax.annotation.Nullable;

/**
 * A cube's baseline: its {@link SurfaceReduction} when its settling window ended, the terrain as generated plus what
 * its springs and falling blocks did in the first moments. Edits are judged against it rather than against the terrain
 * preview, which at full detail differs from native generation in places (plants, boulders, cave mouths). A cube
 * saved before the baseline existed has none; for it, {@link #baselineOrPreview} samples the server's preview instead.
 */
public final class CubeBaselines {

    private CubeBaselines() {
    }

    /** The cube's reduction now, from its column's opacity height map. Server thread. */
    public static int[] reduceNow(Cube cube) {
        IHeightMap heights = ((IColumn) cube.getColumn()).getOpacityIndex();
        ExtendedBlockStorage storage = cube.getStorage();
        World world = cube.getWorld();
        CubePos pos = cube.getCoords();
        return SurfaceReduction.reduce(pos.getMinBlockY(), heights::getTopBlockY, new SurfaceReduction.TopCells() {
            private IBlockState state(int x, int y, int z) {
                return storage == null ? Blocks.AIR.getDefaultState() : storage.get(x, y, z);
            }

            @Override public int colorIndex(int x, int y, int z) {
                return state(x, y, z).getMapColor(world, pos.localToBlock(x, y, z)).colorIndex;
            }

            @Override public int kind(int x, int y, int z) {
                return state(x, y, z).getMaterial().isLiquid() ? SurfaceReduction.KIND_LIQUID : SurfaceReduction.KIND_SOLID;
            }
        });
    }

    /** Takes the cube's baseline now. Server thread, with the cube's surface tracked. */
    public static void capture(Cube cube) {
        cube.setBaseline(reduceNow(cube));
    }

    /**
     * The cube's baseline, or for a cube without one, its reduction as the server's terrain preview draws it.
     *
     * @return null when the cube has no baseline and the world's generator has no preview
     */
    @Nullable
    public static int[] baselineOrPreview(Cube cube, @Nullable ICubicTerrainPreview.Sampler preview) {
        int[] baseline = cube.getBaseline();
        if (baseline != null) {
            return baseline;
        }
        return preview == null ? null : reducePreview(preview, cube.getWorld(), cube.getCoords());
    }

    /**
     * The columns whose top now differs from the baseline, as {@link SurfaceReduction#differing}; null when there is
     * nothing to compare with.
     */
    @Nullable
    public static long[] changedColumns(Cube cube, @Nullable ICubicTerrainPreview.Sampler preview) {
        int[] baseline = baselineOrPreview(cube, preview);
        return baseline == null ? null : SurfaceReduction.differing(reduceNow(cube), baseline);
    }

    /** The reduction of a cube as the preview draws it. The sampler is confined to the calling thread. */
    public static int[] reducePreview(ICubicTerrainPreview.Sampler preview, World world, CubePos pos) {
        int minY = pos.getMinBlockY();
        int[] reduction = new int[SurfaceReduction.COLUMNS];
        TopRun top = new TopRun();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                top.reset();
                int blockX = pos.getMinBlockX() + x, blockZ = pos.getMinBlockZ() + z;
                preview.sampleColumn(blockX, blockZ, top);
                int localY = top.topY - minY;
                if (top.state != null && localY >= 0 && localY < 16) {
                    IBlockState state = top.state;
                    int color = state.getMapColor(world, new BlockPos(blockX, top.topY, blockZ)).colorIndex;
                    int kind = state.getMaterial().isLiquid() ? SurfaceReduction.KIND_LIQUID : SurfaceReduction.KIND_SOLID;
                    reduction[SurfaceReduction.index(x, z)] = SurfaceReduction.entry(localY, color, kind);
                }
            }
        }
        return reduction;
    }

    /** Keeps the highest run of an opaque state; runs arrive bottom to top. */
    private static final class TopRun implements ICubicTerrainPreview.ColumnConsumer {
        @Nullable IBlockState state;
        int topY;

        void reset() {
            state = null;
            topY = Integer.MIN_VALUE;
        }

        @Override public void begin(Biome biome) {
        }

        @SuppressWarnings("deprecation")
        @Override public void addRun(int minimumY, int maximumYExclusive, IBlockState runState) {
            if (maximumYExclusive > minimumY && runState.getLightOpacity() > 0) {
                state = runState;
                topY = maximumYExclusive - 1;
            }
        }
    }
}
