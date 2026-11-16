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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TestPoolStopCheck {

    private final List<String> lines = new ArrayList<>();
    private final PoolStopCheck check = new PoolStopCheck((pool, live, after) -> lines.add(pool + " " + live + " " + after));

    @Test
    public void aStopIsCountedOnceAfterItsDelay() {
        AtomicInteger live = new AtomicInteger(3);
        check.stopped("Client build pool", live::get, 0);
        check.poll(PoolStopCheck.DELAY_NANOS - 1);
        assertTrue("Not before its delay", lines.isEmpty());
        live.set(0);
        check.poll(PoolStopCheck.DELAY_NANOS);
        assertEquals(List.of("Client build pool 0 1000"), lines);
        check.poll(5 * PoolStopCheck.DELAY_NANOS);
        assertEquals("Counted once", 1, lines.size());
        assertEquals(0, check.pending());
    }

    @Test
    public void aThreadLeftBehindIsCountedAgainUntilItEnds() {
        AtomicInteger live = new AtomicInteger(3);
        long second = PoolStopCheck.DELAY_NANOS;
        check.stopped("Client preview pool", live::get, 0);
        check.poll(second);
        check.poll(2 * second);
        live.set(1);
        check.poll(5 * second);
        live.set(0);
        check.poll(10 * second);
        assertEquals(List.of("Client preview pool 3 1000", "Client preview pool 3 2000", "Client preview pool 1 5000",
                "Client preview pool 0 10000"), lines);
        assertEquals("No count after it reached none", 0, check.pending());
    }

    @Test
    public void aThreadThatNeverEndsIsCountedAtTheLastDelayAndNoMore() {
        check.stopped("Client preview pool", () -> 2, 0);
        for (int s = 1; s <= 30; s++) {
            check.poll(s * PoolStopCheck.DELAY_NANOS);
        }
        assertEquals(PoolStopCheck.DELAYS_NANOS.length, lines.size());
        assertEquals("Client preview pool 2 10000", lines.get(lines.size() - 1));
        assertEquals(0, check.pending());
    }

    @Test
    public void tooManyStopsAreCountedAtOnceRatherThanKept() {
        for (int i = 0; i < PoolStopCheck.MAX_PENDING + 2; i++) {
            check.stopped("pool " + i, () -> 0, i);
        }
        assertEquals(PoolStopCheck.MAX_PENDING, check.pending());
        assertEquals("The two oldest are counted as they are pushed out", 2, lines.size());
        assertTrue(lines.get(0).startsWith("pool 0 0 "));
        assertTrue(lines.get(1).startsWith("pool 1 0 "));
    }

    @Test
    public void aStoppedBuildPoolHandsItsThreadsIn() throws Exception {
        ClientPreviewBuildPool build = new ClientPreviewBuildPool(2, "test");
        build.pool().submit(() -> { }).get();
        assertTrue(build.liveThreads() >= 1);
        build.shutdown(5000);
        assertEquals("Client preview build pool", PoolStopCheck.INSTANCE.latest());
        assertEquals("Its threads end with the stop", 0, build.liveThreads());
    }
}
