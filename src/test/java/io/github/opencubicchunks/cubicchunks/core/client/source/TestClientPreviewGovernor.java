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

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class TestClientPreviewGovernor {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static long[] all(int count, double ms) {
        long[] durations = new long[count];
        Arrays.fill(durations, (long) (ms * 1e6));
        return durations;
    }

    @Test public void itStartsAtHalfTheThreadsAndRaisesOneAtATimeWhileFramesAreShort() {
        ClientPreviewGovernor governor = new ClientPreviewGovernor(4, 25);
        assertEquals(2, governor.permits());
        long[] frames = all(120, 8);
        assertEquals("A job waited and frames are well inside the budget", 3,
                governor.update(0, frames, new long[0], true, true));
        assertEquals("Not again until the change has settled", 3,
                governor.update(SECOND, frames, new long[0], true, true));
        assertEquals(4, governor.update(3 * SECOND, frames, new long[0], true, true));
        assertEquals("Never over the threads", 4, governor.update(9 * SECOND, frames, new long[0], true, true));
    }

    @Test public void eachChangeSaysWhatDecidedIt() {
        ClientPreviewGovernor governor = new ClientPreviewGovernor(4, 25);
        governor.update(0, all(120, 30), new long[0], true, true);
        assertEquals("cut: frame p95 at or over 25.0 ms", governor.lastReason());
        assertEquals(30.0, governor.lastFrameMs(), 0.01);
        assertEquals(0.0, governor.lastTickMs(), 0.01);
        governor.update(SECOND, all(120, 2), all(40, 50), true, true);
        assertEquals("held at the floor", governor.lastReason());
        assertEquals(50.0, governor.lastTickMs(), 0.01);
        governor.update(4 * SECOND, all(120, 2), new long[0], true, false);
        assertEquals("raised: work waited and frames and ticks are under their raise marks", governor.lastReason());
        governor.update(5 * SECOND, all(120, 2), new long[0], false, false);
        assertEquals("unchanged", governor.lastReason());
    }

    @Test public void withNoServerTicksOnlyFramesDecide() {
        // A dedicated server in its own process: its ticks are not read, so they never cut or hold back a raise.
        ClientPreviewGovernor governor = new ClientPreviewGovernor(4, 25);
        assertEquals(3, governor.update(0, all(120, 2), new long[0], true, true));
    }

    @Test public void nothingWaitingRaisesNothing() {
        ClientPreviewGovernor governor = new ClientPreviewGovernor(4, 25);
        assertEquals(2, governor.update(0, all(120, 8), new long[0], false, true));
    }

    @Test public void aLongFrameTailHalvesThePermitsButNeverBelowOne() {
        ClientPreviewGovernor governor = new ClientPreviewGovernor(8, 25);
        assertEquals(4, governor.permits());
        long[] frames = all(120, 10);
        // More than one frame in twenty over the budget.
        for (int i = 0; i < 8; i++) {
            frames[i * 10] = 40_000_000L;
        }
        assertEquals(2, governor.update(0, frames, new long[0], true, true));
        assertEquals(1, governor.update(SECOND, frames, new long[0], true, true));
        assertEquals(1, governor.update(2 * SECOND, frames, new long[0], true, true));
        assertEquals("Long frames with no job running are not this pool's doing", 1,
                new ClientPreviewGovernor(2, 25).update(0, frames, new long[0], true, false));
    }

    @Test public void framesBetweenTheRaiseMarkAndTheBudgetHoldThePermits() {
        ClientPreviewGovernor governor = new ClientPreviewGovernor(4, 25);
        assertEquals(2, governor.update(0, all(120, 22), new long[0], true, true));
    }

    @Test public void aBusyIntegratedServerCutsAndHoldsBackRaises() {
        ClientPreviewGovernor governor = new ClientPreviewGovernor(8, 25);
        long[] frames = all(120, 8);
        assertEquals("The server's tick tail is at its own limit", 2,
                governor.update(0, frames, all(40, 46), true, true));
        assertEquals("Ticks between the marks hold", 2, governor.update(5 * SECOND, frames, all(40, 42), true, true));
        assertEquals(3, governor.update(10 * SECOND, frames, all(40, 30), true, true));
    }

    @Test public void theTailIsTheNinetyFifthPercentileOfTheLatest() {
        long[] durations = all(200, 5);
        durations[199] = 90_000_000L;
        assertEquals("One long frame in 120 is under the tail", 5.0, ClientPreviewGovernor.p95Ms(durations, 120), 1e-9);
        assertEquals(0.0, ClientPreviewGovernor.p95Ms(new long[0], 120), 0);
    }
}
