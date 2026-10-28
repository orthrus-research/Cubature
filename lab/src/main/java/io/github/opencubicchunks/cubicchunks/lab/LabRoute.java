package io.github.opencubicchunks.cubicchunks.lab;
public final class LabRoute {
    private LabRoute() { }
    public static double height(int minY, int maxYExclusive, int step) {
        long span = maxYExclusive - (long) minY;
        if (step < 0 || span < 16) throw new IllegalArgumentException("Invalid route");
        int phase = step % 10;
        int waypoint = phase <= 5 ? phase : 10 - phase;
        return minY + 8L + (span - 16) * waypoint / 5.0;
    }
}
