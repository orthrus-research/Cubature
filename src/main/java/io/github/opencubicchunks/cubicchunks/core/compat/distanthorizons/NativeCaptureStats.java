package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

/** Client-owner counters only: no clock, admission decision, queue or retained column. */
final class NativeCaptureStats {
    long passes, initiallyPending, visits, unloaded, debounced, updating, attempts, nullSnapshots, submissions;
    long failedCopies, failedSubmissions, interruptedVisits, unvisited;
    long exhaustedPasses, countLimitedPasses, timeLimitedPasses, failedPasses;
    long passNanos, copyNanos, applyNanos, maximumPassNanos;
    int residualPending;

    private int initialPending, passVisits, passUnloaded, passDebounced, passUpdating;
    private int passAttempts, completedCopies, passNulls, startedSubmissions, passSubmissions;
    private long passCopyNanos, passApplyNanos;

    void begin(int pending) {
        initialPending = pending;
        passVisits = passUnloaded = passDebounced = passUpdating = 0;
        passAttempts = completedCopies = passNulls = startedSubmissions = passSubmissions = 0;
        passCopyNanos = passApplyNanos = 0;
    }

    void visited() { passVisits++; }
    void unloaded() { passUnloaded++; }
    void debounced() { passDebounced++; }
    void updating() { passUpdating++; }
    void attempted() { passAttempts++; }

    void copied(long nanos, boolean wasNull) {
        completedCopies++;
        passCopyNanos += nanos;
        if (wasNull) passNulls++;
    }

    void submitting() { startedSubmissions++; }
    void submitted() { passSubmissions++; }
    void applied(long nanos) { passApplyNanos += nanos; }

    void finish(boolean completed, boolean hasMore, boolean countReached, int pending, long nanos) {
        passes++;
        initiallyPending += initialPending;
        visits += passVisits;
        unloaded += passUnloaded;
        debounced += passDebounced;
        updating += passUpdating;
        attempts += passAttempts;
        nullSnapshots += passNulls;
        submissions += passSubmissions;
        failedCopies += passAttempts - completedCopies;
        failedSubmissions += startedSubmissions - passSubmissions;
        interruptedVisits += passVisits - passUnloaded - passDebounced - passUpdating - passAttempts;
        unvisited += initialPending - passVisits;
        residualPending = pending;
        passNanos += nanos;
        copyNanos += passCopyNanos;
        applyNanos += passApplyNanos;
        maximumPassNanos = Math.max(maximumPassNanos, nanos);
        // Match the loop's short-circuit order; reaching two successes does not prove spare time.
        if (!completed) failedPasses++;
        else if (!hasMore) exhaustedPasses++;
        else if (countReached) countLimitedPasses++;
        else timeLimitedPasses++;
    }

    void reset() {
        passes = initiallyPending = visits = unloaded = debounced = updating = attempts = nullSnapshots = submissions = 0;
        failedCopies = failedSubmissions = interruptedVisits = unvisited = 0;
        exhaustedPasses = countLimitedPasses = timeLimitedPasses = failedPasses = 0;
        passNanos = copyNanos = applyNanos = maximumPassNanos = 0;
        residualPending = 0;
        begin(0);
    }

    String summary() {
        return "passes=" + passes + " initial=" + initiallyPending + " visits=" + visits + " unloaded=" + unloaded
                + " debounced=" + debounced + " updating=" + updating + " attempts=" + attempts
                + " nulls=" + nullSnapshots + " submitted=" + submissions + " failedCopies=" + failedCopies
                + " failedSubmissions=" + failedSubmissions + " interruptedVisits=" + interruptedVisits
                + " unvisited=" + unvisited + " residual=" + residualPending
                + " exits(exhausted/count/time/failed)=" + exhaustedPasses + "/" + countLimitedPasses
                + "/" + timeLimitedPasses + "/" + failedPasses + " passMs=" + passNanos / 1_000_000.0
                + " copyMs=" + copyNanos / 1_000_000.0 + " applyMs=" + applyNanos / 1_000_000.0
                + " maxPassMs=" + maximumPassNanos / 1_000_000.0;
    }
}
