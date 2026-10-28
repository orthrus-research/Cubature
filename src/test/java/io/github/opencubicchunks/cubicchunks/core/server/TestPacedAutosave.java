package io.github.opencubicchunks.cubicchunks.core.server;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class TestPacedAutosave {
    @Test public void entryLimitCoalescesCoordinatesAndContinuesInOrder() {
        PacedAutosave<Integer> queue = new PacedAutosave<>(); List<Integer> saved = new ArrayList<>();
        for (int i = 0; i < 8; i++) { queue.add(i); queue.add(i); }
        assertEquals(3, queue.tick(3, 100, () -> true, saved::add, () -> 0));
        assertEquals(5, queue.size()); queue.tick(8, 100, () -> true, saved::add, () -> 0);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7), saved);
    }
    @Test public void timeBudgetAllowsOneSlowSnapshotThenYields() {
        PacedAutosave<Integer> queue = new PacedAutosave<>(); AtomicLong clock = new AtomicLong();
        queue.add(1); queue.add(2);
        assertEquals(1, queue.tick(32, 2, () -> true, key -> clock.addAndGet(10), clock::get));
        assertEquals(1, queue.size());
    }
    @Test public void backlogOrFailureDefersWithoutDroppingCoordinates() {
        PacedAutosave<Integer> queue = new PacedAutosave<>(); queue.add(1);
        assertEquals(0, queue.tick(32, 2, () -> false, key -> fail(), () -> 0)); assertEquals(1, queue.size());
        assertEquals(1, queue.tick(32, 2, () -> true, key -> { }, () -> 0));
    }
    @Test public void failedSnapshotStaysQueuedForRetry() {
        PacedAutosave<Integer> queue = new PacedAutosave<>(); queue.add(1);
        try { queue.tick(32, 2, () -> true, key -> { throw new IllegalStateException("snapshot failed"); }, () -> 0); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(1, queue.size());
        assertEquals(1, queue.tick(32, 2, () -> true, key -> { }, () -> 0));
    }
    @Test public void forcedSaveCanClearTheContinuationAndScopeRestoresAfterFailure() {
        PacedAutosave<Integer> queue = new PacedAutosave<>(); queue.add(1); queue.clear(); assertEquals(0, queue.size());
        assertFalse(AutosaveScope.isActive());
        try { AutosaveScope.run(() -> { assertTrue(AutosaveScope.isActive()); throw new IllegalStateException(); }); fail(); }
        catch (IllegalStateException expected) { }
        assertFalse(AutosaveScope.isActive());
        AutosaveScope.run(() -> { AutosaveScope.run(() -> assertTrue(AutosaveScope.isActive())); assertTrue(AutosaveScope.isActive()); });
        assertFalse(AutosaveScope.isActive());
    }
}
