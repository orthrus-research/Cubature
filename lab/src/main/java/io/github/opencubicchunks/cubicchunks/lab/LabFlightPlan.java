package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;

/** A wall-clock offered load; server tick slowdown must not lengthen the flight. */
final class LabFlightPlan {
    final int seconds, settleSeconds;
    final double speed;
    final long durationNanos;
    LabFlightPlan(JsonObject workload) {
        if (workload == null || !workload.has("spectatorFlight")) throw new IllegalArgumentException("Missing spectator flight");
        JsonObject value = workload.getAsJsonObject("spectatorFlight");
        seconds = value.get("durationSeconds").getAsInt();
        settleSeconds = value.get("settleSeconds").getAsInt();
        speed = value.get("blocksPerSecond").getAsDouble();
        if (seconds != 60 || settleSeconds < 10 || settleSeconds > 60 || !Double.isFinite(speed) || speed != 16)
            throw new IllegalArgumentException("Flight requires 60 seconds at 16 blocks/s and 10..60 settling seconds");
        durationNanos = seconds * 1000000000L;
    }
    boolean finished(long start, long now) { return now - start >= durationNanos; }
    double velocityPerClientTick() { return speed / 20.0; }
    boolean validOutcome(long elapsed, double distance) {
        return elapsed >= durationNanos && elapsed <= durationNanos + 1000000000L
                && Math.abs(distance - seconds * speed) <= 16;
    }
}
