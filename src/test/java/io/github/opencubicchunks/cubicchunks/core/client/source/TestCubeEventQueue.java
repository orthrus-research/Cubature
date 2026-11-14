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

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TestCubeEventQueue {

    private static final CubeEventQueue.Loaded EVERYWHERE = (x, y, z) -> true;
    private static final CubeEventQueue.Loaded NOWHERE = (x, y, z) -> false;

    private static List<String> drain(CubeEventQueue queue) {
        List<String> out = new ArrayList<>();
        queue.drain(new ICubeSourceListener() {
            @Override public void worldAttached(long worldIdentity) {
            }

            @Override public void worldDetached(long worldIdentity) {
            }

            @Override public void cubeLoaded(int x, int y, int z) {
                out.add("loaded " + x + " " + y + " " + z);
            }

            @Override public void cubeUnloaded(int x, int y, int z) {
                out.add("unloaded " + x + " " + y + " " + z);
            }

            @Override public void cubeChanged(int x, int y, int z, int kinds, int minX, int minY, int minZ, int maxX,
                    int maxY, int maxZ) {
                out.add("changed " + x + " " + y + " " + z + " kinds " + kinds + " " + minX + "," + minY + "," + minZ
                        + " to " + maxX + "," + maxY + "," + maxZ);
            }
        });
        return out;
    }

    @Test
    public void changesToOneCubeMerge() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.changed(1, 2, 3, ICubeSourceListener.BLOCKS, 4, 5, 6, 4, 5, 6, NOWHERE);
        queue.changed(1, 2, 3, ICubeSourceListener.LIGHT, 8, 2, 9, 8, 2, 9, NOWHERE);
        List<String> events = drain(queue);
        assertEquals(1, events.size());
        assertEquals("changed 1 2 3 kinds 3 4,2,6 to 8,5,9", events.get(0));
    }

    @Test
    public void interiorChangeRaisesNoNeighbor() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.changed(0, 0, 0, ICubeSourceListener.BLOCKS, 7, 7, 7, 8, 8, 8, EVERYWHERE);
        assertEquals(1, drain(queue).size());
    }

    @Test
    public void faceChangeRaisesTheNeighborOnThatFace() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.changed(0, 0, 0, ICubeSourceListener.BLOCKS, 15, 7, 7, 15, 7, 7, EVERYWHERE);
        List<String> events = drain(queue);
        assertEquals(2, events.size());
        assertEquals("changed 1 0 0 kinds 8 0,0,0 to 15,15,15", events.get(1));
    }

    @Test
    public void cornerChangeRaisesSevenNeighbors() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.changed(0, 0, 0, ICubeSourceListener.BLOCKS, 0, 0, 0, 0, 0, 0, EVERYWHERE);
        assertEquals(8, drain(queue).size());
    }

    @Test
    public void arrivalRaisesEveryLoadedNeighborAndAbsorbsItsOwnChanges() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.loaded(0, 0, 0, EVERYWHERE);
        queue.changed(0, 0, 0, ICubeSourceListener.BLOCKS, 0, 0, 0, 15, 15, 15, EVERYWHERE);
        List<String> events = drain(queue);
        assertEquals(27, events.size());
        assertEquals("loaded 0 0 0", events.get(0));
    }

    @Test
    public void neighborsThatAreNotLoadedGetNothing() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.loaded(0, 0, 0, NOWHERE);
        queue.unloaded(0, 0, 0, NOWHERE);
        assertEquals(2, drain(queue).size());
    }

    @Test
    public void orderIsKeptAndAChangeAfterRemovalAndReturnIsAbsorbedByTheReturn() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.changed(5, 5, 5, ICubeSourceListener.BLOCKS, 3, 3, 3, 3, 3, 3, NOWHERE);
        queue.unloaded(5, 5, 5, NOWHERE);
        queue.loaded(5, 5, 5, NOWHERE);
        queue.changed(5, 5, 5, ICubeSourceListener.LIGHT, 1, 1, 1, 1, 1, 1, NOWHERE);
        List<String> events = drain(queue);
        assertEquals(3, events.size());
        assertEquals("changed 5 5 5 kinds 1 3,3,3 to 3,3,3", events.get(0));
        assertEquals("unloaded 5 5 5", events.get(1));
        assertEquals("loaded 5 5 5", events.get(2));
    }

    @Test
    public void drainingEmptiesTheQueue() {
        CubeEventQueue queue = new CubeEventQueue();
        queue.loaded(0, 0, 0, NOWHERE);
        drain(queue);
        assertEquals(0, drain(queue).size());
    }
}
