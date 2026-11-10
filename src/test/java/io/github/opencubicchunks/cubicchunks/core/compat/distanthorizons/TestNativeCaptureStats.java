package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestNativeCaptureStats {
    @Test public void aPassPartitionsVisitedEntriesWithoutCallingPendingEligible() {
        NativeCaptureStats stats = new NativeCaptureStats();
        stats.begin(8);
        stats.visited(); stats.unloaded();
        stats.visited(); stats.debounced();
        stats.visited(); stats.updating();
        stats.visited(); stats.attempted(); stats.copied(10, true);
        stats.visited(); stats.attempted(); stats.copied(20, false);
        stats.submitting(); stats.applied(30); stats.submitted();
        stats.finish(true, true, false, 6, 2_100_000);

        assertEquals(1, stats.passes);
        assertEquals(5, stats.visits);
        assertEquals(8, stats.initiallyPending);
        assertEquals(1, stats.unloaded);
        assertEquals(1, stats.debounced);
        assertEquals(1, stats.updating);
        assertEquals(2, stats.attempts);
        assertEquals(1, stats.nullSnapshots);
        assertEquals(1, stats.submissions);
        assertEquals(3, stats.unvisited);
        assertEquals(stats.initiallyPending, stats.visits + stats.unvisited);
        assertEquals(6, stats.residualPending);
        assertEquals(stats.visits, stats.unloaded + stats.debounced + stats.updating + stats.attempts);
        assertEquals(0, stats.failedCopies + stats.failedSubmissions + stats.interruptedVisits);
        assertEquals(30, stats.copyNanos);
        assertEquals(30, stats.applyNanos);
        assertEquals(2_100_000, stats.passNanos);
        assertEquals(1, stats.timeLimitedPasses);
    }

    @Test public void nullSnapshotsRemainDistinctFromFailedSubmissions() {
        NativeCaptureStats stats = new NativeCaptureStats();
        stats.begin(2);
        stats.visited(); stats.attempted(); stats.copied(10, true);
        stats.visited(); stats.attempted(); stats.copied(20, false);
        stats.submitting(); stats.applied(40); // DH threw; submitted() must not be called.
        stats.finish(false, false, false, 2, 100);

        assertEquals(2, stats.attempts);
        assertEquals(1, stats.nullSnapshots);
        assertEquals(0, stats.submissions);
        assertEquals(0, stats.failedCopies);
        assertEquals(1, stats.failedSubmissions);
        assertEquals(1, stats.failedPasses);
        assertEquals(0, stats.exhaustedPasses + stats.countLimitedPasses + stats.timeLimitedPasses);
        assertEquals(2, stats.residualPending);
    }

    @Test public void aThrowingCopyIsNotACompletedNullCopy() {
        NativeCaptureStats stats = new NativeCaptureStats();
        stats.begin(3);
        stats.visited(); stats.attempted();
        stats.finish(false, true, false, 3, 500);

        assertEquals(1, stats.failedCopies);
        assertEquals(0, stats.nullSnapshots + stats.submissions + stats.failedSubmissions);
        assertEquals(0, stats.copyNanos + stats.applyNanos);
        assertEquals(2, stats.unvisited);
        assertEquals(1, stats.failedPasses);
    }

    @Test public void aFailureBeforeClassificationLeavesTheVisitExplicitlyUnclassified() {
        NativeCaptureStats stats = new NativeCaptureStats();
        stats.begin(4);
        stats.visited();
        stats.finish(false, true, false, 4, 50);

        assertEquals(1, stats.interruptedVisits);
        assertEquals(0, stats.attempts + stats.debounced + stats.updating + stats.unloaded);
        assertEquals(3, stats.unvisited);
        assertEquals(1, stats.failedPasses);
    }

    @Test public void exitReasonsFollowTheExistingShortCircuitOrderDespiteAnOverrun() {
        NativeCaptureStats stats = new NativeCaptureStats();
        stats.begin(3);
        for (int i = 0; i < 2; i++) {
            stats.visited(); stats.attempted(); stats.copied(10, false);
            stats.submitting(); stats.applied(20); stats.submitted();
        }
        stats.finish(true, true, true, 1, 2_500_000);
        assertEquals(1, stats.countLimitedPasses);
        assertEquals(0, stats.timeLimitedPasses);
        assertEquals(2_500_000, stats.maximumPassNanos);
        assertEquals(1, stats.unvisited);

        stats.begin(1);
        stats.finish(true, true, false, 1, 2_000_000);
        assertEquals(1, stats.timeLimitedPasses);
        assertEquals(4_500_000, stats.passNanos);
        assertEquals(2_500_000, stats.maximumPassNanos);

        stats.begin(2);
        for (int i = 0; i < 2; i++) {
            stats.visited(); stats.attempted(); stats.copied(10, false);
            stats.submitting(); stats.applied(20); stats.submitted();
        }
        stats.finish(true, false, true, 0, 3_000_000);
        assertEquals(1, stats.exhaustedPasses); // hasNext is tested before the count limit.
        assertEquals(1, stats.countLimitedPasses);
        assertEquals(stats.passes, stats.exhaustedPasses + stats.countLimitedPasses
                + stats.timeLimitedPasses + stats.failedPasses);
    }

    @Test public void entriesAddedDuringSubmissionAreNotInventedAsVisitedInitialEntries() {
        NativeCaptureStats stats = new NativeCaptureStats();
        stats.begin(1);
        stats.visited(); stats.attempted(); stats.copied(10, false);
        stats.submitting(); stats.applied(20); stats.submitted();
        stats.finish(true, false, false, 2, 100);
        assertEquals(0, stats.unvisited);
        assertEquals(2, stats.residualPending);
        assertEquals(1, stats.submissions);
    }

    @Test public void resettingForAnotherWorldDropsTotalsAndAnyPartialPass() {
        NativeCaptureStats stats = new NativeCaptureStats();
        stats.begin(2);
        stats.visited(); stats.attempted(); stats.copied(10, true);
        stats.finish(true, true, false, 2, 100);
        stats.begin(100);
        stats.visited(); stats.attempted(); // A partial old-world pass is discarded.
        stats.reset();
        stats.begin(0);
        stats.finish(true, false, false, 0, 5);

        assertEquals(1, stats.passes);
        assertEquals(0, stats.initiallyPending + stats.visits + stats.attempts + stats.nullSnapshots + stats.unvisited);
        assertEquals(0, stats.failedCopies + stats.failedSubmissions + stats.failedPasses);
        assertEquals(0, stats.copyNanos + stats.applyNanos);
        assertEquals(5, stats.passNanos);
        assertEquals(0, stats.residualPending);
        assertTrue(stats.summary().contains("exits(exhausted/count/time/failed)=1/0/0/0"));
    }
}
