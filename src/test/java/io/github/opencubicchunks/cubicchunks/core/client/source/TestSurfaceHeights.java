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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class TestSurfaceHeights {

    private static final int NONE = ICubeSource.NO_SURFACE;
    private static final int FLOOR = -1664;

    private static IBlockState stone;
    private static IBlockState dirt;
    private static IBlockState water;
    private static IBlockState leaves;
    private static IBlockState log;
    private static IBlockState grass;
    private static IBlockState air;

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        stone = Blocks.STONE.getDefaultState();
        dirt = Blocks.DIRT.getDefaultState();
        water = Blocks.WATER.getDefaultState();
        leaves = Blocks.LEAVES.getDefaultState();
        log = Blocks.LOG.getDefaultState();
        grass = Blocks.TALLGRASS.getDefaultState();
        air = Blocks.AIR.getDefaultState();
    }

    /** A chunk column of loaded cubes: stone up to a level everywhere, with blocks set over it. */
    private static final class Loaded implements SurfaceHeights.LoadedColumn {
        private final Map<Integer, IBlockState> set = new HashMap<>();
        private final int[] tops = new int[256];
        private final int stoneTop;
        /** Cubes below this Y are not loaded. */
        int loadedFrom = FLOOR;
        /** Cubes from this Y up are not loaded. */
        int loadedTo = 4096;

        Loaded(int stoneTopBlock) {
            stoneTop = stoneTopBlock;
            java.util.Arrays.fill(tops, stoneTopBlock);
        }

        Loaded put(int x, int y, int z, IBlockState state) {
            set.put(key(x, y, z), state);
            int i = z << 4 | x;
            if (state.getLightOpacity() != 0) {
                tops[i] = Math.max(tops[i], y);
            }
            return this;
        }

        private static int key(int x, int y, int z) {
            return (y - FLOOR) << 8 | z << 4 | x;
        }

        @Override public int top(int localX, int localZ) {
            return tops[localZ << 4 | localX];
        }

        @Override public IBlockState state(int localX, int blockY, int localZ) {
            if (blockY < loadedFrom || blockY >= loadedTo) {
                return null;
            }
            IBlockState state = set.get(key(localX, blockY, localZ));
            return state != null ? state : blockY <= stoneTop ? stone : air;
        }

        @Override public int floor() {
            return FLOOR;
        }
    }

    /** A preview whose every column is stone to a level, then water to another, then air. */
    private static ICubicTerrainPreview preview(int groundTop, int waterTop, int[] columnsSampled) {
        return () -> (x, z, consumer) -> {
            columnsSampled[0]++;
            consumer.begin(null);
            consumer.addRun(FLOOR, groundTop, stone);
            if (waterTop > groundTop) {
                consumer.addRun(groundTop, waterTop, water);
            }
            consumer.addRun(Math.max(groundTop, waterTop), 384, air);
        };
    }

    private final int[] ground = new int[256];
    private final int[] liquid = new int[256];
    private final boolean[] fromLoaded = new boolean[256];

    @Test public void aTreeCrownAndItsTrunkAreNotGround() {
        Loaded column = new Loaded(70);
        for (int y = 71; y <= 76; y++) {
            column.put(3, y, 4, log);
        }
        column.put(3, 77, 4, leaves).put(4, 76, 4, leaves).put(5, 71, 4, grass);
        int answered = new SurfaceHeights().fill(column, null, 0, 0, ground, liquid, fromLoaded);
        assertEquals(256, answered);
        assertEquals("Under the trunk", 71, ground[4 << 4 | 3]);
        assertEquals("Under a leaf with air below it", 71, ground[4 << 4 | 4]);
        assertEquals("Under tall grass", 71, ground[4 << 4 | 5]);
        assertEquals("Open ground", 71, ground[0]);
        assertEquals(NONE, liquid[4 << 4 | 3]);
        assertTrue(fromLoaded[4 << 4 | 3]);
    }

    @Test public void anOverhangsTopIsTheGround() {
        Loaded column = new Loaded(60);
        column.put(8, 90, 8, stone).put(8, 91, 8, dirt);
        new SurfaceHeights().fill(column, null, 0, 0, ground, liquid, fromLoaded);
        assertEquals("The top of the overhang, not the ground open to the sky beside it", 92, ground[8 << 4 | 8]);
        assertEquals(61, ground[8 << 4 | 9]);
    }

    @Test public void waterOverABedGivesBoth() {
        Loaded column = new Loaded(40);
        for (int y = 41; y <= 62; y++) {
            column.put(1, y, 2, water);
        }
        new SurfaceHeights().fill(column, null, 0, 0, ground, liquid, fromLoaded);
        assertEquals("The bed", 41, ground[2 << 4 | 1]);
        assertEquals("The water's surface", 63, liquid[2 << 4 | 1]);
        assertEquals("No liquid over dry ground", NONE, liquid[0]);
        assertTrue(fromLoaded[2 << 4 | 1]);
    }

    @Test public void anEditedColumnFollowsTheLoadedCubesNotThePreview() {
        int[] sampled = new int[1];
        Loaded column = new Loaded(70);
        column.put(0, 71, 0, stone).put(0, 72, 0, stone);
        SurfaceHeights heights = new SurfaceHeights();
        int answered = heights.fill(column, preview(71, 0, sampled), 5, -3, ground, liquid, fromLoaded);
        assertEquals(256, answered);
        assertEquals("Two blocks placed on the generated ground", 73, ground[0]);
        assertEquals(71, ground[1]);
        assertTrue(fromLoaded[0]);
        assertEquals("Nothing was asked of the preview", 0, sampled[0]);
    }

    @Test public void anUnloadedColumnIsAnsweredByThePreviewAndSaysSo() {
        int[] sampled = new int[1];
        SurfaceHeights heights = new SurfaceHeights();
        int answered = heights.fill(null, preview(90, 101, sampled), 2, 2, ground, liquid, fromLoaded);
        assertEquals(0, answered);
        assertEquals(90, ground[17]);
        assertEquals(101, liquid[17]);
        assertFalse(fromLoaded[17]);
        assertEquals(256, sampled[0]);
        heights.fill(null, preview(90, 101, sampled), 2, 2, ground, liquid, fromLoaded);
        assertEquals("A second preview is sampled afresh", 512, sampled[0]);
    }

    @Test public void aPreviewsAnswersAreKept() {
        int[] sampled = new int[1];
        ICubicTerrainPreview preview = preview(90, 0, sampled);
        SurfaceHeights heights = new SurfaceHeights();
        heights.fill(null, preview, 2, 2, ground, liquid, fromLoaded);
        heights.fill(null, preview, 2, 2, ground, liquid, fromLoaded);
        assertEquals(256, sampled[0]);
        assertEquals(90, ground[0]);
        assertEquals("Dry ground has no liquid", NONE, liquid[0]);
    }

    @Test public void aColumnWhoseSurfaceCubeIsNotLoadedFallsToThePreview() {
        int[] sampled = new int[1];
        Loaded column = new Loaded(70);
        // The server's height index knows the top; the client holds only cubes far below it.
        column.loadedTo = 32;
        int answered = new SurfaceHeights().fill(column, preview(71, 0, sampled), 0, 0, ground, liquid, fromLoaded);
        assertEquals(0, answered);
        assertEquals(71, ground[0]);
        assertFalse(fromLoaded[0]);
        // Water whose bed lies in a cube that is not loaded: neither value is the loaded cubes'.
        Loaded sea = new Loaded(20);
        for (int y = 21; y <= 62; y++) {
            sea.put(0, y, 0, water);
        }
        sea.loadedFrom = 48;
        new SurfaceHeights().fill(sea, preview(21, 63, sampled), 0, 0, ground, liquid, fromLoaded);
        assertFalse(fromLoaded[0]);
        assertEquals(21, ground[0]);
        assertEquals(63, liquid[0]);
    }

    @Test public void withNoPreviewAnUnloadedColumnIsUnknown() {
        int answered = new SurfaceHeights().fill(null, null, 0, 0, ground, liquid, fromLoaded);
        assertEquals(0, answered);
        for (int i = 0; i < 256; i++) {
            assertEquals(NONE, ground[i]);
            assertEquals(NONE, liquid[i]);
            assertFalse(fromLoaded[i]);
        }
    }

    @Test public void aWithdrawnPreviewAnswersNothing() {
        ICubicTerrainPreview gone = () -> (x, z, consumer) -> {
            throw new TerrainPreviewWithdrawnException();
        };
        new SurfaceHeights().fill(null, gone, 0, 0, ground, liquid, fromLoaded);
        assertEquals(NONE, ground[0]);
        assertEquals(NONE, ground[255]);
    }

    @Test public void groundIsWhatBlocksMovementLessPlantsLogsAndLiquid() {
        assertTrue(SurfaceHeights.ground(stone));
        assertTrue(SurfaceHeights.ground(Blocks.SAND.getDefaultState()));
        assertTrue("Glass blocks movement", SurfaceHeights.ground(Blocks.GLASS.getDefaultState()));
        assertTrue("Planks are not a tree", SurfaceHeights.ground(Blocks.PLANKS.getDefaultState()));
        assertFalse(SurfaceHeights.ground(leaves));
        assertFalse(SurfaceHeights.ground(log));
        assertFalse(SurfaceHeights.ground(Blocks.LOG2.getDefaultState()));
        assertFalse(SurfaceHeights.ground(Blocks.CACTUS.getDefaultState()));
        assertFalse(SurfaceHeights.ground(Blocks.PUMPKIN.getDefaultState()));
        assertFalse(SurfaceHeights.ground(grass));
        assertFalse(SurfaceHeights.ground(water));
        assertFalse(SurfaceHeights.ground(Blocks.LAVA.getDefaultState()));
        assertFalse(SurfaceHeights.ground(air));
        assertTrue(SurfaceHeights.liquid(Blocks.LAVA.getDefaultState()));
    }
}
