package io.github.opencubicchunks.cubicchunks.core.server;

/** Reused per world; columns and cubes share one soft deadline, cubes also have an exact quota. */
final class GenerationBudget {
    private long started, nanos;
    private int cubes;

    void reset(long now, long nanos, int cubes) {
        this.started = now;
        this.nanos = nanos;
        this.cubes = cubes;
    }

    boolean hasTime(long now) { return now - started < nanos; }
    boolean canGenerateCube(long now) { return cubes > 0 && hasTime(now); }
    void generatedCube() { --cubes; }
}
