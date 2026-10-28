/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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
package io.github.opencubicchunks.cubicchunks.core.worldgen;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class TestWorldgenWatchdogEntry {
    @Test public void reportIsClaimedOnceOnlyAfterTheLimit() {
        WorldgenWatchdogEntry entry = new WorldgenWatchdogEntry(100);
        assertFalse(entry.claimReport(109, 10));
        assertFalse(entry.claimReport(110, 10));
        assertTrue(entry.claimReport(111, 10));
        assertTrue(entry.isReported());
        assertFalse(entry.claimReport(1000, 10));
    }

    @Test public void samplesRetainOnlyTheMostRecentBoundedHistory() {
        WorldgenWatchdogEntry entry = new WorldgenWatchdogEntry(0);
        for (int i = 0; i < WorldgenWatchdogEntry.MAX_SAMPLES + 5; i++) {
            entry.sample(stack(i));
        }
        List<StackTraceElement[]> samples = entry.samples();
        assertEquals(WorldgenWatchdogEntry.MAX_SAMPLES, samples.size());
        assertEquals(5, samples.get(0)[0].getLineNumber());
        assertEquals(WorldgenWatchdogEntry.MAX_SAMPLES + 4,
                samples.get(samples.size() - 1)[0].getLineNumber());
        samples.clear();
        assertEquals(WorldgenWatchdogEntry.MAX_SAMPLES, entry.samples().size());
    }

    @Test public void aReportedEntryDoesNotCollectMoreSamples() {
        WorldgenWatchdogEntry entry = new WorldgenWatchdogEntry(0);
        entry.sample(stack(1));
        assertTrue(entry.claimReport(2, 1));
        entry.sample(stack(2));
        assertEquals(1, entry.samples().size());
        assertEquals(1, entry.samples().get(0)[0].getLineNumber());
    }

    @Test public void reportingRetainsNestedOwnershipUntilTheOuterExit() {
        WorldgenWatchdogEntry entry = new WorldgenWatchdogEntry(100);
        entry.enter();
        entry.enter();
        assertTrue(entry.claimReport(111, 10));
        assertFalse(entry.leave());
        assertFalse(entry.leave());
        entry.enter();
        assertFalse(entry.claimReport(1000, 10));
        assertFalse(entry.leave());
        assertTrue(entry.leave());
        WorldgenWatchdogEntry next = new WorldgenWatchdogEntry(1000);
        assertTrue(next.claimReport(1011, 10));
        assertTrue(next.leave());
    }

    @Test public void elapsedTimeUsesNanoTimeWraparoundArithmetic() {
        WorldgenWatchdogEntry entry = new WorldgenWatchdogEntry(Long.MAX_VALUE - 4);
        assertEquals(10, entry.elapsedNanos(Long.MIN_VALUE + 5));
        assertTrue(entry.claimReport(Long.MIN_VALUE + 5, 9));
    }

    private static StackTraceElement[] stack(int line) {
        return new StackTraceElement[] {new StackTraceElement("Generator", "generate", "Generator.java", line)};
    }
}
