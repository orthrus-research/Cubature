package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * How many Distant Horizons preview tiles may run at once while the server keeps its tick within budget. A tick has 50
 * ms at 20 ticks a second, and previews may use what the tick's tail leaves: the p95 tick stays at or under 45 ms
 * (2026-10-30). A running tile costs the server thread through allocation and memory traffic rather than through the
 * cores it takes, so neither CPU load nor the server thread's waits show it; the governor reads the ticks themselves.
 * Once a second, a p95 of the last 20 ticks at 45 ms or more halves the permits if tiles ran in that second; with tiles
 * waiting, two seconds without a change and a p95 of the last 40 ticks under 40 ms add one. Permits never fall below
 * one, so previews slow but never stop. While a native view is behind, the admission gate holds every tile instead, and
 * the slow ticks of a join are the native view's own, so they cut nothing.
 */
final class PreviewGovernor {
    static final double CUT_MS = 45, RAISE_MS = 40;
    static final int CUT_TICKS = 20, RAISE_TICKS = 40;
    static final long SETTLE = TimeUnit.SECONDS.toNanos(2);

    private final int ceiling;
    private int permits;
    private long lastChange = Long.MIN_VALUE;

    PreviewGovernor(int ceiling) {
        this.ceiling = Math.max(1, ceiling);
        permits = Math.max(1, this.ceiling / 2);
    }

    /**
     * One update a second.
     *
     * @param ticks the latest tick durations in nanoseconds, oldest first
     * @param waiting whether a tile waited for a permit since the last update
     * @param ran whether a tile ran since the last update
     * @return the permits now
     */
    int update(long now, long[] ticks, boolean waiting, boolean ran) {
        if (ran && p95Ms(ticks, CUT_TICKS) >= CUT_MS) {
            permits = Math.max(1, permits / 2);
            lastChange = now;
        } else if (waiting && permits < ceiling && (lastChange == Long.MIN_VALUE || now - lastChange >= SETTLE)
                && ticks.length >= RAISE_TICKS && p95Ms(ticks, RAISE_TICKS) < RAISE_MS) {
            permits++;
            lastChange = now;
        }
        return permits;
    }

    int permits() {
        return permits;
    }

    /** The p95 of the last {@code count} ticks, in milliseconds (0 without ticks). */
    static double p95Ms(long[] ticks, int count) {
        int n = Math.min(count, ticks.length);
        if (n == 0) return 0;
        long[] last = Arrays.copyOfRange(ticks, ticks.length - n, ticks.length);
        Arrays.sort(last);
        return last[(int) Math.ceil(.95 * n) - 1] / 1e6;
    }
}
