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

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import org.junit.Test;

public class TestSettledState {

    private static final int[] BOX = {0, 4, 0, 12, 12};
    private static final int[] MOVED = {1, 4, 0, 12, 12};

    @Test
    public void quietTicksCountTicksWithNothing() {
        SettledState state = new SettledState();
        state.tick(false);
        state.tick(false);
        assertEquals(2, state.quietTicks());
        state.busy();
        assertEquals(2, state.quietTicks());
        state.tick(false);
        assertEquals(0, state.quietTicks());
        state.tick(false);
        assertEquals(1, state.quietTicks());
    }

    @Test
    public void queuedLightKeepsTheViewBusy() {
        SettledState state = new SettledState();
        state.tick(false);
        state.tick(true);
        assertEquals(0, state.quietTicks());
        state.tick(true);
        assertEquals(0, state.quietTicks());
        state.tick(false);
        assertEquals(1, state.quietTicks());
    }

    @Test
    public void theBoxIsSentOnlyWhenTheServerNamesTheCurrentOne() {
        SettledState state = new SettledState();
        state.boxSent(BOX);
        assertFalse(state.boxSent());
        state.box(BOX);
        state.boxSent(MOVED);
        assertFalse(state.boxSent());
        state.boxSent(BOX);
        assertTrue(state.boxSent());
        assertTrue(state.takeSentChanged());
        assertFalse(state.takeSentChanged());
    }

    @Test
    public void aNewBoxIsNotSentAndNotQuiet() {
        SettledState state = new SettledState();
        state.box(BOX);
        state.boxSent(BOX);
        state.takeSentChanged();
        state.tick(false);
        state.tick(false);
        assertEquals(1, state.quietTicks());
        state.box(MOVED);
        assertFalse(state.boxSent());
        assertTrue(state.takeSentChanged());
        state.tick(false);
        assertEquals(0, state.quietTicks());
        // The same box stated again changes nothing.
        state.boxSent(MOVED);
        state.takeSentChanged();
        state.tick(false);
        state.box(MOVED.clone());
        assertTrue(state.boxSent());
        state.tick(false);
        assertEquals(2, state.quietTicks());
    }

    @Test
    public void aSentReportForAnOldBoxAfterAMoveIsIgnored() {
        SettledState state = new SettledState();
        state.box(BOX);
        state.box(MOVED);
        state.boxSent(BOX);
        assertFalse(state.boxSent());
    }

    @Test
    public void clearForgetsEverything() {
        SettledState state = new SettledState();
        state.box(BOX);
        state.boxSent(BOX);
        state.tick(false);
        state.tick(false);
        state.clear();
        assertEquals(0, state.quietTicks());
        assertFalse(state.boxSent());
        assertFalse(state.takeSentChanged());
        state.boxSent(BOX);
        assertFalse(state.boxSent());
    }

    private static ICubeSource source(int quiet, int[] box, boolean sent) {
        return new ICubeSource() {
            @Override public void setListener(ICubeSourceListener l) { }
            @Override public boolean attached() { return true; }
            @Override public long worldIdentity() { return 1; }
            @Override public int minCubeY() { return -8; }
            @Override public int maxCubeY() { return 8; }
            @Override public boolean hasSkyLight() { return true; }
            @Override public boolean isLoaded(int x, int y, int z) { return false; }
            @Override public long version(int x, int y, int z) { return -1; }
            @Override public void forEachLoaded(CubeVisitor visitor) { }
            @Override public ICubeSnapshot snapshot(int x, int y, int z) { throw new UnsupportedOperationException(); }
            @Override public ICubicTerrainPreview terrainPreview() { return null; }
            @Override public int quietTicks() { return quiet; }
            @Override public int[] heldBox() { return box; }
            @Override public boolean heldBoxSent() { return sent; }
        };
    }

    @Test
    public void settledNeedsTheBoxSentAndEnoughQuiet() {
        assertTrue(source(20, BOX, true).settled(20));
        assertFalse(source(19, BOX, true).settled(20));
        assertFalse(source(400, BOX, false).settled(20));
        // From a server that states no box, the quiet ticks alone decide.
        assertTrue(source(20, null, false).settled(20));
        assertFalse(source(3, null, false).settled(20));
    }
}
