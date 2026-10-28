package io.github.opencubicchunks.cubicchunks.core.server;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.*;

public class TestMaintenanceIndex {
    @Test public void lanesAdvanceIndependentlyAndDuplicateRegistrationKeepsAge() {
        MaintenanceIndex<Object> index = new MaintenanceIndex<>();
        Object a = new Object(), b = new Object(), c = new Object();
        index.add(a); index.add(b); index.add(c);
        assertSame(a, index.next(false));
        index.add(b);
        assertSame(b, index.next(false));
        assertSame(a, index.next(true));
        assertSame(c, index.next(false));
        assertSame(a, index.next(false));
        assertSame(b, index.next(true));
        assertEquals(3, index.size());
    }

    @Test public void removingEitherCursorAndLastEntryDoesNotKeepDeadObjects() {
        MaintenanceIndex<Object> index = new MaintenanceIndex<>();
        Object a = new Object(), b = new Object(), c = new Object();
        index.add(a); index.add(b); index.add(c);
        assertSame(a, index.next(false));
        index.remove(a); index.remove(b);
        assertSame(c, index.next(true)); assertSame(c, index.next(false));
        index.remove(c); index.remove(c);
        assertEquals(0, index.size());
        assertNull(index.next(false)); assertNull(index.next(true));
        index.add(a); assertSame(a, index.next(false)); assertSame(a, index.next(true));
    }

    @Test public void identityKeepsEqualCoordinatesSeparateUntilReplaced() {
        MaintenanceIndex<String> index = new MaintenanceIndex<>();
        String a = new String("coordinate"), b = new String("coordinate");
        index.add(a); index.add(b); index.remove(a);
        assertEquals(1, index.size()); assertSame(b, index.next(false));
    }

    @Test public void fullSweepIteratorRemovalUpdatesBothContinuationLanes() {
        MaintenanceIndex<Integer> index = new MaintenanceIndex<>();
        List<Integer> loaded = new ArrayList<>(Arrays.asList(1, 2, 3));
        loaded.forEach(index::add);
        Iterator<Integer> iterator = index.track(loaded.iterator());
        while (iterator.hasNext()) if (iterator.next() != 2) iterator.remove();
        assertEquals(Arrays.asList(2), loaded);
        assertEquals(1, index.size());
        assertEquals(Integer.valueOf(2), index.next(false));
        assertEquals(Integer.valueOf(2), index.next(true));
    }

    @Test public void repeatedMutationNeverReturnsRemovedEntriesOrStarvesSurvivors() {
        MaintenanceIndex<Object> index = new MaintenanceIndex<>();
        List<Object> live = new ArrayList<>(); Random random = new Random(9);
        for (int round = 0; round < 1000; round++) {
            Object added = new Object(); live.add(added); index.add(added);
            if (live.size() > 25) index.remove(live.remove(random.nextInt(live.size())));
            for (boolean save : new boolean[]{false, true}) {
                Set<Object> seen = new HashSet<>();
                for (int i = 0; i < live.size(); i++) {
                    Object next = index.next(save); assertTrue(live.contains(next)); assertTrue(seen.add(next));
                }
                assertEquals(live.size(), seen.size());
            }
        }
    }

    @Test public void continuousInsertionCannotStayAheadOfEitherOffsetCursor() {
        MaintenanceIndex<Object> index = new MaintenanceIndex<>();
        Object a = new Object(), b = new Object(); index.add(a); index.add(b);
        assertSame(a, index.next(false));
        boolean gcSawA = false, saveSawB = false;
        for (int i = 0; i < 20; i++) {
            index.add(new Object());
            gcSawA |= index.next(false) == a;
            saveSawB |= index.next(true) == b;
        }
        assertTrue(gcSawA); assertTrue(saveSawB);
    }
}
