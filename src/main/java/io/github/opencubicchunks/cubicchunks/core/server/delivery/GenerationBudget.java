package io.github.opencubicchunks.cubicchunks.core.server.delivery;

/** Reused per world; columns and cubes share one soft deadline, cubes also have an exact quota. */
public final class GenerationBudget {
    private long started, nanos;
    private int cubes;

    public void reset(long now, long nanos, int cubes) {
        this.started = now;
        this.nanos = nanos;
        this.cubes = cubes;
    }

    public boolean hasTime(long now) { return now - started < nanos; }
    public boolean canGenerateCube(long now) { return cubes > 0 && hasTime(now); }
    public void generatedCube() { --cubes; }
}
