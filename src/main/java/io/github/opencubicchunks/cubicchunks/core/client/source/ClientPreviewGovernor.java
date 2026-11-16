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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * How many client preview jobs may run at once: the counterpart of the server's
 * {@code PreviewGovernor}, with the client's frames as its first signal and, in single player, the
 * integrated server's ticks as its second. Updated once a second.
 *
 * <p>It halves the permits, never below one, when jobs ran and the frames' tail is over the frame
 * budget or the server's tick tail is over the server pool's own limit. It adds one when a job
 * waited for a permit, the last change has settled, and both tails are under their raise marks.
 */
final class ClientPreviewGovernor {

    /** The server pool's figures: a tick tail at or over 45 ms cuts, under 40 ms may raise. */
    static final double CUT_TICK_MS = 45, RAISE_TICK_MS = 40;
    static final int CUT_TICKS = 20, RAISE_TICKS = 40;
    /** Frames looked at: about a second at 120 frames a second, two at 60. */
    static final int FRAMES = 120;
    /** A raise needs the frames' tail under this share of the budget. */
    static final double RAISE_SHARE = 0.8;
    static final long SETTLE = TimeUnit.SECONDS.toNanos(2);

    private final int ceiling;
    private final double frameBudgetMs;
    private int permits;
    private long lastChange = Long.MIN_VALUE;

    /**
     * @param ceiling       the pool's threads
     * @param frameBudgetMs the frame time the client should keep, in milliseconds
     */
    ClientPreviewGovernor(int ceiling, double frameBudgetMs) {
        this.ceiling = Math.max(1, ceiling);
        this.frameBudgetMs = frameBudgetMs;
        permits = Math.max(1, this.ceiling / 2);
    }

    /**
     * One update a second.
     *
     * @param frames  the latest frame durations in nanoseconds, oldest first
     * @param ticks   the integrated server's latest tick durations, oldest first; empty on a remote server
     * @param waiting whether a job waited for a permit since the last update
     * @param ran     whether a job ran since the last update
     * @return the permits now
     */
    int update(long now, long[] frames, long[] ticks, boolean waiting, boolean ran) {
        double frameTail = p95Ms(frames, FRAMES);
        if (ran && (frameTail >= frameBudgetMs || p95Ms(ticks, CUT_TICKS) >= CUT_TICK_MS)) {
            permits = Math.max(1, permits / 2);
            lastChange = now;
        } else if (waiting && permits < ceiling && (lastChange == Long.MIN_VALUE || now - lastChange >= SETTLE)
                && frames.length > 0 && frameTail < RAISE_SHARE * frameBudgetMs
                && p95Ms(ticks, RAISE_TICKS) < RAISE_TICK_MS) {
            permits++;
            lastChange = now;
        }
        return permits;
    }

    int permits() {
        return permits;
    }

    /** The p95 of the last {@code count} durations, in milliseconds (0 without any). */
    static double p95Ms(long[] durations, int count) {
        int n = Math.min(count, durations.length);
        if (n == 0) {
            return 0;
        }
        long[] last = Arrays.copyOfRange(durations, durations.length - n, durations.length);
        Arrays.sort(last);
        return last[(int) Math.ceil(.95 * n) - 1] / 1e6;
    }
}
