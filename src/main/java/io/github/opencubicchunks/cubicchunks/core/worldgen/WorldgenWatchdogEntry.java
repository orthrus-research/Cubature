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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Per-thread nested generation state, guarded by the watchdog's entries monitor. */
final class WorldgenWatchdogEntry {
    static final int MAX_SAMPLES = 32;

    private final long startTime;
    private final ArrayDeque<StackTraceElement[]> samples = new ArrayDeque<>();
    private int depth = 1;
    private boolean reported;

    WorldgenWatchdogEntry(long startTime) {
        this.startTime = startTime;
    }

    void enter() {
        depth++;
    }

    boolean leave() {
        if (depth > 0) {
            depth--;
        }
        return depth == 0;
    }

    long elapsedNanos(long now) {
        return now - startTime;
    }

    void sample(StackTraceElement[] stack) {
        if (reported) {
            return;
        }
        if (samples.size() == MAX_SAMPLES) {
            samples.removeFirst();
        }
        samples.addLast(stack);
    }

    List<StackTraceElement[]> samples() {
        return new ArrayList<>(samples);
    }

    boolean isReported() {
        return reported;
    }

    boolean claimReport(long now, long limitNanos) {
        if (reported || elapsedNanos(now) <= limitNanos) {
            return false;
        }
        reported = true;
        return true;
    }
}
