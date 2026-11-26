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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestSurfaceBaseline {

    /** A flat cube-sized world: every column's top at {@code topY}, of one color and kind, with overrides. */
    private static final class Flat implements SurfaceReduction.ColumnTops, SurfaceReduction.TopCells {
        final int[] tops = new int[256];
        final int[] colors = new int[256];
        final int[] kinds = new int[256];

        Flat(int topY, int color) {
            Arrays.fill(tops, topY);
            Arrays.fill(colors, color);
        }

        @Override public int topY(int x, int z) {
            return tops[SurfaceReduction.index(x, z)];
        }

        @Override public int colorIndex(int x, int y, int z) {
            return colors[SurfaceReduction.index(x, z)];
        }

        @Override public int kind(int x, int y, int z) {
            return kinds[SurfaceReduction.index(x, z)];
        }
    }

    @Test public void entriesKeepHeightColorAndKind() {
        int e = SurfaceReduction.entry(13, 42, SurfaceReduction.KIND_LIQUID);
        assertTrue(SurfaceReduction.isPresent(e));
        assertEquals(13, SurfaceReduction.localY(e));
        assertEquals(42, SurfaceReduction.colorIndex(e));
        assertEquals(SurfaceReduction.KIND_LIQUID, SurfaceReduction.kind(e));
        assertTrue("Even height 0, color 0, solid is present", SurfaceReduction.isPresent(SurfaceReduction.entry(0, 0, 0)));
        assertFalse(SurfaceReduction.isPresent(SurfaceReduction.ABSENT));
    }

    @Test public void onlyTheCubeTheSurfacePassesThroughHasEntries() {
        Flat world = new Flat(70, 1); // grass at y=70: in the cube from 64 to 79
        int[] surface = SurfaceReduction.reduce(64, world, world);
        int[] below = SurfaceReduction.reduce(48, world, world);
        int[] above = SurfaceReduction.reduce(80, world, world);
        assertFalse(SurfaceReduction.isEmpty(surface));
        assertEquals(6, SurfaceReduction.localY(surface[SurfaceReduction.index(3, 9)]));
        assertTrue("A buried cube draws nothing from far away", SurfaceReduction.isEmpty(below));
        assertTrue(SurfaceReduction.isEmpty(above));
        assertTrue("A column with no opaque block at all", SurfaceReduction.isEmpty(SurfaceReduction.reduce(64,
                (x, z) -> Integer.MIN_VALUE, world)));
    }

    @Test public void aHouseShowsAsChangedColumnsOnBothCubesItCrosses() {
        Flat before = new Flat(70, 1);
        int[] lowBaseline = SurfaceReduction.reduce(64, before, before);
        int[] highBaseline = SurfaceReduction.reduce(80, before, before);
        assertEquals(SurfaceReduction.hash(lowBaseline), SurfaceReduction.hash(SurfaceReduction.reduce(64, before, before)));

        // a 5x5 house whose roof is at y=84, in the cube above
        Flat after = new Flat(70, 1);
        for (int x = 2; x < 7; x++) for (int z = 2; z < 7; z++) {
            after.tops[SurfaceReduction.index(x, z)] = 84;
            after.colors[SurfaceReduction.index(x, z)] = 13;
        }
        long[] low = SurfaceReduction.differing(SurfaceReduction.reduce(64, after, after), lowBaseline);
        long[] high = SurfaceReduction.differing(SurfaceReduction.reduce(80, after, after), highBaseline);
        assertEquals("The ground under the house is no longer the top", 25, SurfaceReduction.count(low));
        assertEquals("The roof is a new top in the cube above", 25, SurfaceReduction.count(high));
        assertArrayEquals("The same columns", low, high);
        assertTrue((low[0] & (1L << SurfaceReduction.index(2, 2))) != 0);
        assertNotEquals(SurfaceReduction.hash(lowBaseline), SurfaceReduction.hash(SurfaceReduction.reduce(64, after, after)));
    }

    @Test public void aColorOrKindChangeAtTheSameHeightCounts() {
        Flat before = new Flat(70, 1);
        int[] baseline = SurfaceReduction.reduce(64, before, before);
        Flat paved = new Flat(70, 1);
        paved.colors[SurfaceReduction.index(0, 0)] = 11;
        paved.kinds[SurfaceReduction.index(15, 15)] = SurfaceReduction.KIND_LIQUID;
        assertEquals(2, SurfaceReduction.count(SurfaceReduction.differing(SurfaceReduction.reduce(64, paved, paved), baseline)));
    }

    @Test public void settlingDeadlinesComeOutInOrderAndOnlyWhenDue() {
        SettlingQueue<String> queue = new SettlingQueue<>();
        queue.add("a", 200);
        queue.add("b", 201);
        queue.add("a", 400); // fully populated later: queued again
        List<String> out = new ArrayList<>();
        queue.expire(199, out::add);
        assertTrue(out.isEmpty());
        queue.expire(201, out::add);
        assertEquals(Arrays.asList("a", "b"), out);
        assertEquals(1, queue.size());
        queue.expire(1000, out::add);
        assertEquals(Arrays.asList("a", "b", "a"), out);
    }
}
