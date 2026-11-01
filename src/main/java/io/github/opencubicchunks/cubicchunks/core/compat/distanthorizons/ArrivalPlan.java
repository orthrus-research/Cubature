/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's order for Distant Horizons section requests (LOD-DELIVERY step 5). DH asks for the nearest missing
 * sections first; in flight those are the ones the native view is about to cover, while coarse sections ahead wait
 * behind them. This plan orders requests by when the player reaches each section, lets sections at or coarser than
 * the on-time detail go before finer ones, and holds back a section the native view covers now, or will cover before
 * its data could arrive. Holding back only delays a request: a section the prediction got wrong is asked for as soon
 * as it no longer qualifies, and nothing here changes what any section contains.
 *
 * <p>The on-time detail follows the coarse sections' own arrivals while the player moves. When more than a tenth of
 * them arrive after the player has come close enough to want finer detail there, it steps one level coarser; when none
 * are late and coarse requests are under half of those sent, it steps one level finer. Once the player has been still
 * for a second it is 1-block, so every section ranks nearest first, as DH would order them, and the detail flight gave
 * up comes back where the player looks; moving again restores the in-flight detail. While the server can afford it,
 * finer sections inside DH's 1-block ring come next after the on-time ones, before every other finer section, so the
 * terrain nearest the native view fills first. Moving, the ring sweeps up new 1-block sections at its diameter times
 * the speed over a section's area each second; it ranks ahead only while that fits in half of the server's answers,
 * so walking it does and in elytra flight it does not (beside a fast path its tiles arrive after the player has passed
 * and coverage starves). Detail levels are DH section detail levels: 6 is 1-block cells, 8 is 4-block cells. The
 * server serves a cubic world's requests in the order they arrive (MixinDistantHorizonsServeInOrder), so this order
 * reaches its preview generator.
 *
 * <p>The client keeps in flight only what the server answers in about 3 s (its answers a second, smoothed over about
 * five, times three), never fewer than 8 and never more than DH's limit, so its choice is at most a few seconds old
 * when a tile starts.
 */
public final class ArrivalPlan {
    public static final int FINEST = 6, COARSEST = 10, START = 8;
    /** Returned by {@link #key} for a section to hold back. */
    public static final long HOLD = Long.MAX_VALUE;
    static final double MIN_APPROACH = 4, MOVING = 2, NATIVE_KEEPING_UP = 0.95, HORIZON_SECONDS = 10, START_SECONDS = 5;
    static final int LATE_WINDOW = 20, MIN_ARRIVALS = 10, MAX_TRACKED = 8192;
    static final long COARSER_AFTER = 5_000_000_000L, FINER_AFTER = 15_000_000_000L, VELOCITY_NANOS = 500_000_000L;
    static final long REST_NANOS = 1_000_000_000L;
    /** A 1-block section's width in blocks, and the share of the server's answers the 1-block ring may take moving. */
    static final int SECTION_BLOCKS = 64;
    static final double RING_SHARE = 0.5;
    static final int INFLIGHT_MIN = 8;
    static final double QUEUE_SECONDS = 3;

    /** The player as of the last client tick. */
    static final class State {
        final double x, z, vx, vz, halfWidth;
        final boolean nativeKeepingUp;
        State(double x, double z, double vx, double vz, double halfWidth, boolean nativeKeepingUp) {
            this.x = x; this.z = z; this.vx = vx; this.vz = vz; this.halfWidth = halfWidth; this.nativeKeepingUp = nativeKeepingUp;
        }
        double speed() { return Math.hypot(vx, vz); }
    }

    private volatile State state;
    private volatile int floor = START;
    /** The on-time detail to restore when a resting player moves again. */
    private int flightFloor = START;
    private boolean resting;
    private long stillSince;
    private volatile double unit = 192, base = 2;
    /** Seconds from queueing a request to its data, per detail level; replaced whole on each arrival. */
    private volatile double[] service = filled(START_SECONDS);
    private final ConcurrentHashMap<Long, Long> queued = new ConcurrentHashMap<>();
    private volatile double answerRate;
    private int answersThisSecond;
    private long rateSecond;
    private boolean observed;
    private long lastNanos, lastStep;
    private double lastPx, lastPz, vx, vz;
    private final boolean[] late = new boolean[LATE_WINDOW];
    private int arrivals, sends, coarseSends;

    private static double[] filled(double seconds) {
        double[] out = new double[COARSEST + 1];
        Arrays.fill(out, seconds);
        return out;
    }

    /** DH's detail falloff: detail k (0 = 1-block) is wanted from {@code unit * base^k} blocks out. */
    public void rings(double unit, double base) { this.unit = unit; this.base = base; }

    public int floor() { return floor; }
    public double speed() { State s = state; return s == null ? 0 : s.speed(); }
    public double serviceSeconds(int detail) { return service[clamp(detail)]; }
    public double answerRate() { return answerRate; }

    /** How many requests may be in flight, given DH's own limit. */
    public int inflight(int limit) {
        return Math.min(limit, Math.max(INFLIGHT_MIN, (int) Math.ceil(answerRate * QUEUE_SECONDS)));
    }

    /**
     * One client tick: the player's block position, the native view's half-width in blocks and the share of its
     * columns loaded. Velocity is smoothed over about half a second; a jump of more than 64 blocks is a teleport.
     */
    public synchronized void observe(long nanos, double px, double pz, double halfWidth, double nativeLoadedShare) {
        if (observed && nanos > lastNanos) {
            double dt = (nanos - lastNanos) / 1e9, a = Math.min(1, (nanos - lastNanos) / (double) VELOCITY_NANOS);
            if (Math.hypot(px - lastPx, pz - lastPz) > 64) { vx = 0; vz = 0; }
            else { vx += ((px - lastPx) / dt - vx) * a; vz += ((pz - lastPz) / dt - vz) * a; }
        }
        observed = true; lastNanos = nanos; lastPx = px; lastPz = pz;
        if (rateSecond == 0) rateSecond = nanos;
        while (nanos - rateSecond >= 1_000_000_000L) {
            answerRate += (answersThisSecond - answerRate) * 0.2;
            answersThisSecond = 0;
            rateSecond += 1_000_000_000L;
        }
        state = new State(px, pz, vx, vz, halfWidth, nativeLoadedShare >= NATIVE_KEEPING_UP);
        rest(nanos, Math.hypot(vx, vz) < MOVING);
    }

    /** Still for a second: 1-block on time, nearest first. Moving again: the in-flight detail. */
    private void rest(long nanos, boolean still) {
        if (!still) {
            stillSince = 0;
            if (resting) {
                resting = false;
                floor = flightFloor;
                reset(nanos);
            }
        } else if (stillSince == 0) {
            stillSince = nanos;
        } else if (!resting && nanos - stillSince >= REST_NANOS) {
            resting = true;
            flightFloor = floor;
            floor = FINEST;
            reset(nanos);
        }
    }

    /**
     * The sort key for a section: lower goes first, {@link #HOLD} holds it back. Sections at or coarser than the
     * on-time detail come first, then finer ones inside DH's 1-block ring while the server can afford them, then the
     * other finer ones; within each, the sooner the player reaches the section the earlier.
     */
    public long key(int detail, int minX, int minZ, int width) {
        State s = state;
        if (s == null) return 0;
        double cover = coverSeconds(s, minX, minZ, width);
        boolean moving = s.speed() >= MOVING;
        // DH draws no LOD where native terrain stands; moving with the native view behind, its corners may need one.
        if (cover == 0 && (s.nativeKeepingUp || !moving)) return HOLD;
        if (moving && s.nativeKeepingUp && cover <= Math.min(HORIZON_SECONDS, service[clamp(detail)])) return HOLD;
        double dx = minX + width / 2.0 - s.x, dz = minZ + width / 2.0 - s.z, length = Math.hypot(dx, dz);
        double approach = moving && length > 0 ? (dx * s.vx + dz * s.vz) / length : 0;
        double reach = reach(s, minX, minZ, width);
        long millis = (long) Math.min(1L << 40, reach / Math.max(approach, MIN_APPROACH) * 1000);
        long rank = detail >= floor ? 0 : reach < unit * base && ringAffordable(s) ? 1 : 2;
        return rank << 41 | millis;
    }

    /** Whether the 1-block sections the ring sweeps up each second fit in half of the server's answers. */
    boolean ringAffordable(State s) {
        double sweep = 2 * unit * base * s.speed() / ((double) SECTION_BLOCKS * SECTION_BLOCKS);
        return sweep <= answerRate * RING_SHARE;
    }

    /** Whether a key ranks with the coarse sections, those at or coarser than the on-time detail. */
    public static boolean coarse(long key) { return key != HOLD && key >>> 41 == 0; }

    /** Whether a key ranks with the finer sections inside DH's 1-block ring. */
    public static boolean nearRing(long key) { return key != HOLD && key >>> 41 == 1; }

    /** Chebyshev blocks from the player to the nearest point of a section; 0 inside it. */
    static double reach(State s, int minX, int minZ, int width) {
        return Math.max(0, Math.max(Math.abs(minX + width / 2.0 - s.x), Math.abs(minZ + width / 2.0 - s.z)) - width / 2.0);
    }

    /**
     * Seconds until the native view's square, moving with the player, holds the whole section: 0 if it does now,
     * {@link Double#POSITIVE_INFINITY} if the current travel never brings it inside.
     */
    static double coverSeconds(State s, int minX, int minZ, int width) {
        double margin = s.halfWidth - width / 2.0;
        if (margin < 0) return Double.POSITIVE_INFINITY;
        double[] x = axis(minX + width / 2.0 - s.x, s.vx, margin), z = axis(minZ + width / 2.0 - s.z, s.vz, margin);
        if (x == null || z == null) return Double.POSITIVE_INFINITY;
        double from = Math.max(0, Math.max(x[0], z[0])), to = Math.min(x[1], z[1]);
        return from <= to ? from : Double.POSITIVE_INFINITY;
    }

    /** The times at which |offset - v t| <= margin, or null for none. */
    private static double[] axis(double offset, double v, double margin) {
        if (Math.abs(v) < 1e-9) return Math.abs(offset) <= margin
                ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY} : null;
        double a = (offset - margin) / v, b = (offset + margin) / v;
        return new double[] {Math.min(a, b), Math.max(a, b)};
    }

    private static int clamp(int detail) { return Math.max(FINEST, Math.min(COARSEST, detail)); }

    /** DH's client queued a request for a section. */
    public void queued(long pos, long nanos) {
        if (queued.size() >= MAX_TRACKED) queued.clear();
        queued.putIfAbsent(pos, nanos);
    }

    /** A request was sent; {@code coarse} if it ranked with the coarse sections. */
    public synchronized void sent(boolean coarse) {
        sends++;
        if (coarse) coarseSends++;
    }

    /**
     * A section's data arrived. Its time from queueing updates that detail's service time; a section at or coarser
     * than the on-time detail is late if the player is already inside the ring where finer detail is wanted there.
     */
    public synchronized void arrived(long pos, long nanos, int detail, int minX, int minZ, int width) {
        answersThisSecond++;
        Long since = queued.remove(pos);
        if (since != null && nanos > since) {
            double[] next = service.clone();
            int d = clamp(detail);
            next[d] += ((nanos - since) / 1e9 - next[d]) * 0.2;
            service = next;
        }
        State s = state;
        if (s == null || s.speed() < MOVING || detail < floor) return;
        late[arrivals++ % LATE_WINDOW] = reach(s, minX, minZ, width) < unit * Math.pow(base, detail - FINEST);
        step(nanos);
    }

    private void step(long nanos) {
        if (arrivals < MIN_ARRIVALS) return;
        int n = Math.min(arrivals, LATE_WINDOW), lateCount = 0;
        for (int i = 0; i < n; i++) if (late[i]) lateCount++;
        if (lateCount * 10 > n && floor < COARSEST && nanos - lastStep >= COARSER_AFTER) {
            floor++;
            reset(nanos);
        } else if (lateCount == 0 && floor > FINEST && nanos - lastStep >= FINER_AFTER
                && sends >= MIN_ARRIVALS && coarseSends * 2 < sends) {
            floor--;
            reset(nanos);
        }
    }

    private void reset(long nanos) {
        lastStep = nanos; arrivals = 0; sends = 0; coarseSends = 0;
        Arrays.fill(late, false);
    }
}
