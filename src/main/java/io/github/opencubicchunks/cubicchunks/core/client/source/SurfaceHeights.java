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

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;
import net.minecraft.block.BlockLog;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The ground and liquid surface of block columns, for {@link ICubeSource#surfaceHeights}: from the
 * loaded cubes where they hold a column's surface, from the terrain preview elsewhere.
 *
 * <p>Ground is a block that blocks movement and is neither liquid, a log, nor plant matter (the
 * materials leaves, plants, vine, cactus and gourd, which is what the Distant Horizons integration
 * files as leaves). A height is the Y of the block's top face, the block's Y plus one, as the
 * preview's runs give their tops.
 *
 * <p>Client thread only: the preview's sampler and the cache of its answers are not shared.
 */
final class SurfaceHeights {

    static final int NONE = ICubeSource.NO_SURFACE;
    static final int COLUMNS = 256;

    /**
     * How far below a column's highest light-blocking block the loaded cubes are searched for
     * ground: through a tree, a body of water, or the air under an overhang's lip. A column whose
     * ground lies deeper is answered from the preview.
     */
    static final int SCAN_DEPTH = 256;

    /**
     * How far above that block the loaded cubes are searched for ground that lets light through,
     * such as a glass roof. Cubes that hold nothing are passed over whole.
     */
    static final int SCAN_ABOVE = 256;

    /** Chunk columns whose preview answers are kept: 4 KiB each. */
    private static final int CACHED = 2048;
    private static final int UNSAMPLED = Integer.MIN_VALUE + 1;

    /** A chunk column's loaded cubes, as the search reads them. */
    interface LoadedColumn {

        /** The Y of the column's highest block that dims light, or less than the world's floor. */
        int top(int localX, int localZ);

        /** The block there, or null where the cube is not loaded. */
        @Nullable
        IBlockState state(int localX, int blockY, int localZ);

        /** The world's lowest block. */
        int floor();

        /** The Y of the top block of the highest loaded cube that holds a block, or less than the floor. */
        int highestBlocks();

        /** Whether the cube is loaded and holds any block. */
        boolean holdsBlocks(int cubeY);
    }

    private final Map<Long, int[]> cache = new LinkedHashMap<Long, int[]>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
            return size() > CACHED;
        }
    };
    private final Column column = new Column();
    private final int[] pair = new int[2];
    @Nullable private ICubicTerrainPreview sampled;
    @Nullable private ICubicTerrainPreview.Sampler sampler;

    /** {@link ICubeSource#cubeContent} from what a cube keeps: its block storage and that storage's count. */
    static int content(boolean loaded, @Nullable ExtendedBlockStorage storage) {
        if (!loaded) {
            return ICubeSource.NOT_LOADED;
        }
        return storage == null || storage.isEmpty() ? ICubeSource.EMPTY : ICubeSource.HAS_BLOCKS;
    }

    static boolean liquid(IBlockState state) {
        return state.getMaterial().isLiquid();
    }

    static boolean ground(IBlockState state) {
        Material material = state.getMaterial();
        return material.blocksMovement() && !material.isLiquid() && material != Material.LEAVES
                && material != Material.PLANTS && material != Material.VINE && material != Material.CACTUS
                && material != Material.GOURD && !(state.getBlock() instanceof BlockLog);
    }

    /**
     * Fills the 256 heights of a chunk column, index {@code localZ << 4 | localX}.
     *
     * @param loaded  the column's loaded cubes, or null when the column is not loaded
     * @param preview the world's terrain preview, or null
     * @param sample  whether the preview may be sampled; if not, only answers it gave before are used
     * @return how many block columns were answered from loaded cubes
     */
    int fill(@Nullable LoadedColumn loaded, @Nullable ICubicTerrainPreview preview, int columnX, int columnZ,
            int[] ground, int[] liquid, boolean[] fromLoaded, boolean sample) {
        if (ground.length < COLUMNS || liquid.length < COLUMNS || fromLoaded.length < COLUMNS) {
            throw new IllegalArgumentException("Surface heights need arrays of 256");
        }
        if (preview != sampled) {
            sampled = preview;
            sampler = null;
            cache.clear();
        }
        int[] previewed = null;
        int answered = 0;
        for (int i = 0; i < COLUMNS; i++) {
            int x = i & 15;
            int z = i >> 4;
            if (loaded != null && fromLoaded(loaded, x, z, pair)) {
                ground[i] = pair[0];
                liquid[i] = pair[1];
                fromLoaded[i] = true;
                answered++;
                continue;
            }
            fromLoaded[i] = false;
            ground[i] = NONE;
            liquid[i] = NONE;
            if (preview == null) {
                continue;
            }
            if (previewed == null) {
                previewed = sample ? cache.computeIfAbsent(key(columnX, columnZ), SurfaceHeights::blank)
                        : cache.get(key(columnX, columnZ));
                if (previewed == null) {
                    // Nothing kept for this chunk column, and nothing may be sampled.
                    preview = null;
                    continue;
                }
            }
            if (previewed[i] == UNSAMPLED) {
                if (!sample) {
                    continue;
                }
                if (!sample(preview, (columnX << 4) + x, (columnZ << 4) + z, previewed, i)) {
                    // Withdrawn: this world's preview answers nothing more.
                    preview = null;
                    continue;
                }
            }
            ground[i] = previewed[i];
            liquid[i] = previewed[COLUMNS + i];
        }
        return answered;
    }

    /** Forgets the preview's answers and its sampler. */
    void clear() {
        sampled = null;
        sampler = null;
        cache.clear();
    }

    /**
     * A block column's surface from loaded cubes. First, ground that lets light through, above
     * everything that dims it: the loaded cubes that hold blocks are searched from the highest
     * down to the height index. Then downward from the highest block that dims light to the first
     * ground; liquid met on the way is the liquid surface. False when a cube on that way is not
     * loaded, when nothing in the column dims light and no ground stands in the loaded cubes, or
     * when no ground is found within {@link #SCAN_DEPTH}: the loaded cubes do not say where this
     * column's ground is.
     */
    static boolean fromLoaded(LoadedColumn loaded, int localX, int localZ, int[] out) {
        int floor = loaded.floor();
        int top = Math.max(loaded.top(localX, localZ), floor - 1);
        // With nothing that dims light there is no index to measure from: every cube that holds blocks is searched.
        int high = top < floor ? loaded.highestBlocks()
                : (int) Math.min(loaded.highestBlocks(), (long) top + SCAN_ABOVE);
        for (int y = high; y > top;) {
            if (!loaded.holdsBlocks(y >> 4)) {
                y = (y >> 4 << 4) - 1;
                continue;
            }
            IBlockState state = loaded.state(localX, y, localZ);
            if (state != null && ground(state)) {
                out[0] = y + 1;
                out[1] = NONE;
                return true;
            }
            y--;
        }
        if (top < floor) {
            return false;
        }
        int liquidTop = NONE;
        int lowest = Math.max(floor, top - SCAN_DEPTH);
        for (int y = top; y >= lowest; y--) {
            IBlockState state = loaded.state(localX, y, localZ);
            if (state == null) {
                return false;
            }
            if (ground(state)) {
                out[0] = y + 1;
                out[1] = liquidTop;
                return true;
            }
            if (liquidTop == NONE && liquid(state)) {
                liquidTop = y + 1;
            }
        }
        return false;
    }

    private boolean sample(ICubicTerrainPreview preview, int blockX, int blockZ, int[] into, int index) {
        try {
            if (sampler == null) {
                sampler = preview.createTerrainPreviewSampler();
            }
            column.ground = NONE;
            column.liquid = NONE;
            sampler.sampleColumn(blockX, blockZ, column);
        } catch (TerrainPreviewWithdrawnException withdrawn) {
            return false;
        }
        into[index] = column.ground;
        into[COLUMNS + index] = column.liquid > column.ground ? column.liquid : NONE;
        return true;
    }

    private static int[] blank(Long key) {
        int[] heights = new int[2 * COLUMNS];
        java.util.Arrays.fill(heights, UNSAMPLED);
        return heights;
    }

    private static Long key(int columnX, int columnZ) {
        return (long) columnX << 32 | columnZ & 0xFFFFFFFFL;
    }

    /** Takes a preview column's runs, bottom to top: the highest ground and the highest liquid. */
    private static final class Column implements ICubicTerrainPreview.ColumnConsumer {
        int ground;
        int liquid;

        @Override
        public void begin(Biome biome) {
        }

        @Override
        public void addRun(int minimumY, int maximumYExclusive, IBlockState state) {
            if (ground(state)) {
                ground = Math.max(ground, maximumYExclusive);
            } else if (liquid(state)) {
                liquid = Math.max(liquid, maximumYExclusive);
            }
        }
    }
}
