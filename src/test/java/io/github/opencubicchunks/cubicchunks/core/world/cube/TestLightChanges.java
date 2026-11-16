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
package io.github.opencubicchunks.cubicchunks.core.world.cube;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class TestLightChanges {

    @Test
    public void marksAreTakenOnceInIndexOrder() {
        LightChanges changes = new LightChanges();
        changes.mark(4095);
        changes.mark(0);
        changes.mark(64);
        changes.mark(64);
        short[] out = new short[10];
        assertEquals(3, changes.drainTo(out));
        assertEquals(Arrays.asList((short) 0, (short) 64, (short) 4095),
                Arrays.asList(out[0], out[1], out[2]));
        assertEquals(0, changes.drainTo(out));
    }

    @Test
    public void tooManyAreCountedAndCleared() {
        LightChanges changes = new LightChanges();
        for (int i = 0; i < 4096; i += 2) {
            changes.mark(i);
        }
        short[] out = new short[100];
        assertEquals(2048, changes.drainTo(out));
        assertEquals(0, out[0]);
        assertEquals(198, out[99]);
        assertEquals(0, changes.drainTo(out));
    }

    @Test
    public void marksFromManyThreadsAreAllKept() throws InterruptedException {
        LightChanges changes = new LightChanges();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            int first = t;
            threads[t] = new Thread(() -> {
                for (int i = first; i < 4096; i += 8) {
                    changes.mark(i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        short[] out = new short[4096];
        assertEquals(4096, changes.drainTo(out));
        Set<Short> seen = new HashSet<>();
        for (short index : out) {
            seen.add(index);
        }
        assertEquals(4096, seen.size());
    }
}
