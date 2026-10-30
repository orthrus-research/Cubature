package io.github.opencubicchunks.cubicchunks.core.server.delivery;

/**
 * How many regional preparations may run beyond the players' near regions, set from contention rather than from tick
 * time alone. Once a second it reads the share of the machine's CPU in use and how long the server thread waited during
 * its ticks (wall time less its own CPU time). A slow tick the server thread spends computing for itself does not
 * throttle preparation, since preparation did not cause it; a saturated machine, or a server thread kept waiting while
 * the cores are busy, halves the permits, and spare headroom adds one. With no permit left, one preparation still
 * starts each trickle period, so preparation slows but never stops.
 */
final class PreparationGovernor {
    /** System CPU load at which the machine counts as saturated. */
    static final double SATURATED = .95;
    /** System CPU load below which the machine has spare cores. */
    static final double SPARE = .85;
    /** Server thread wait per tick, in milliseconds, that counts as kept off the cores while they are busy. */
    static final double WAITING_MS = 5;
    /** Server thread wait per tick below which it runs unhindered. */
    static final double CALM_MS = 2;

    private final int maximum;
    private final long trickleNanos;
    private int permits = 1;
    private long lastTrickle = Long.MIN_VALUE;

    PreparationGovernor(int maximum, long trickleNanos) {
        this.maximum = maximum;
        this.trickleNanos = trickleNanos;
        permits = Math.min(1, maximum);
    }

    /**
     * One update a second.
     *
     * @param cpuLoad the system's CPU load, 0 to 1, or negative when it is unknown
     * @param waitMs the server thread's mean wait per tick in milliseconds
     */
    void update(double cpuLoad, double waitMs) {
        boolean known = cpuLoad >= 0;
        boolean contended = known ? cpuLoad >= SATURATED || (waitMs >= WAITING_MS && cpuLoad >= SPARE)
                : waitMs >= WAITING_MS;
        boolean spare = waitMs < CALM_MS && (!known || cpuLoad < SPARE);
        if (contended) permits /= 2;
        else if (spare) permits = Math.min(maximum, permits + 1);
    }

    int permits() {
        return permits;
    }

    /** Whether one more preparation may start while {@code running} such preparations run, at {@code now} nanos. */
    boolean admits(int running, long now) {
        if (running < permits) return true;
        if (permits > 0 || running > 0) return false;
        if (lastTrickle != Long.MIN_VALUE && now - lastTrickle < trickleNanos) return false;
        lastTrickle = now;
        return true;
    }
}
