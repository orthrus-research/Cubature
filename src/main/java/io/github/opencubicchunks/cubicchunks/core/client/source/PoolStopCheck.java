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

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Counts, a moment after a client pool logged its stop line, the pool's threads still alive, and
 * logs that count, so a thread left behind by a stop is measured rather than assumed. A pool hands
 * its stop in here; the client tick polls, with or without a world, so the count is taken without
 * blocking the client thread on a join.
 */
final class PoolStopCheck {

    /** How long after a stop its threads are counted: well past an interrupted job's end. */
    static final long DELAY_NANOS = 1_000_000_000L;
    /** Stops waiting to be counted; more are counted at once rather than kept. */
    static final int MAX_PENDING = 16;

    static final PoolStopCheck INSTANCE = new PoolStopCheck();

    /** What is logged; replaced in tests. */
    interface Report {
        void live(String pool, int live, long afterMillis);
    }

    private static final class Pending {
        final String pool;
        final IntSupplier live;
        final long stoppedAt;

        Pending(String pool, IntSupplier live, long stoppedAt) {
            this.pool = pool;
            this.live = live;
            this.stoppedAt = stoppedAt;
        }
    }

    private final List<Pending> pending = new ArrayList<>();
    private final Report report;

    PoolStopCheck() {
        this((pool, live, after) -> CubicChunks.LOGGER.info("{} threads {} ms after its stop: {} still alive",
                pool, after, live));
    }

    PoolStopCheck(Report report) {
        this.report = report;
    }

    /** A pool logged its stop: its threads are counted {@link #DELAY_NANOS} from now. Any thread. */
    void stopped(String pool, IntSupplier live, long now) {
        Pending first = null;
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) {
                first = pending.remove(0);
            }
            pending.add(new Pending(pool, live, now));
        }
        if (first != null) {
            count(first, now);
        }
    }

    /** Counts the threads of each stop now due. Client tick. */
    void poll(long now) {
        List<Pending> due = new ArrayList<>();
        synchronized (pending) {
            for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
                Pending stop = it.next();
                if (now - stop.stoppedAt >= DELAY_NANOS) {
                    due.add(stop);
                    it.remove();
                }
            }
        }
        for (Pending stop : due) {
            count(stop, now);
        }
    }

    /** The pool of the latest stop still waiting to be counted, or null; for tests. */
    @javax.annotation.Nullable String latest() {
        synchronized (pending) {
            return pending.isEmpty() ? null : pending.get(pending.size() - 1).pool;
        }
    }

    int pending() {
        synchronized (pending) {
            return pending.size();
        }
    }

    private void count(Pending stop, long now) {
        report.live(stop.pool, stop.live.getAsInt(), (now - stop.stoppedAt) / 1_000_000L);
    }
}
