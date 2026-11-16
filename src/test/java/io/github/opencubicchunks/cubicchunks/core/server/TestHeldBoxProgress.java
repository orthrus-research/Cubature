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
package io.github.opencubicchunks.cubicchunks.core.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TestHeldBoxProgress {

    private static String key(int x, int y, int z) {
        return x + " " + y + " " + z;
    }

    @Test
    public void walksExactlyTheBoxOnce() {
        HeldBoxProgress progress = new HeldBoxProgress();
        progress.reset(10, -3, 7, 2, 1);
        List<String> looked = new ArrayList<>();
        assertTrue(progress.advance((x, y, z) -> looked.add(key(x, y, z)), 10_000));
        assertEquals(HeldBoxProgress.size(2, 1), looked.size());
        assertEquals(75, looked.size());
        Set<String> expected = new HashSet<>();
        for (int x = 8; x <= 12; x++) {
            for (int y = -4; y <= -2; y++) {
                for (int z = 5; z <= 9; z++) {
                    expected.add(key(x, y, z));
                }
            }
        }
        assertEquals(expected, new HashSet<>(looked));
        assertTrue(progress.done());
        // Once the box is done nothing more is looked at or reported.
        assertFalse(progress.advance((x, y, z) -> {
            throw new AssertionError();
        }, 10));
    }

    @Test
    public void theBudgetSpreadsTheWalkAndOnlyTheLastCallReports() {
        HeldBoxProgress progress = new HeldBoxProgress();
        progress.reset(0, 0, 0, 2, 2);
        int[] looks = {0};
        HeldBoxProgress.Sent all = (x, y, z) -> ++looks[0] > 0;
        assertFalse(progress.advance(all, 50));
        assertFalse(progress.advance(all, 50));
        assertTrue(progress.advance(all, 50));
        assertEquals(125, looks[0]);
    }

    @Test
    public void aCubeNotYetSentHoldsTheCursorAndIsLookedAtAgain() {
        HeldBoxProgress progress = new HeldBoxProgress();
        progress.reset(0, 0, 0, 1, 1);
        Set<String> missing = new HashSet<>();
        missing.add(key(0, 0, 0));
        List<String> looked = new ArrayList<>();
        HeldBoxProgress.Sent sent = (x, y, z) -> {
            looked.add(key(x, y, z));
            return !missing.contains(key(x, y, z));
        };
        assertFalse(progress.advance(sent, 1000));
        int firstPass = looked.size();
        assertEquals(key(0, 0, 0), looked.get(firstPass - 1));
        looked.clear();
        assertFalse(progress.advance(sent, 1000));
        // Only the waiting cube is looked at again, not those before it.
        assertEquals(1, looked.size());
        missing.clear();
        looked.clear();
        assertTrue(progress.advance(sent, 1000));
        assertEquals(27 - firstPass + 1, looked.size());
    }

    @Test
    public void aNewBoxStartsOverAndTheSameBoxDoesNot() {
        HeldBoxProgress progress = new HeldBoxProgress();
        assertNull(progress.box());
        assertFalse(progress.advance((x, y, z) -> true, 100));
        progress.reset(0, 0, 0, 1, 1);
        assertTrue(progress.advance((x, y, z) -> true, 100));
        progress.reset(0, 0, 0, 1, 1);
        assertTrue(progress.done());
        progress.reset(1, 0, 0, 1, 1);
        assertFalse(progress.done());
        assertArrayEquals(new int[] {1, 0, 0, 1, 1}, progress.box());
        assertTrue(progress.advance((x, y, z) -> true, 100));
    }

    @Test
    public void sizes() {
        assertEquals(27, HeldBoxProgress.size(1, 1));
        assertEquals(25L * 25 * 25, HeldBoxProgress.size(12, 12));
        assertEquals(65L * 65 * 65, HeldBoxProgress.size(32, 32));
    }
}
