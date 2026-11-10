package io.github.opencubicchunks.cubicchunks.core.util;

import io.github.opencubicchunks.cubicchunks.api.util.XYZAddressable;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TestVisibleWatchersSortingList3D {
    private static final class Entry implements BucketSorterEntry, XYZAddressable {
        final int x, y, z;
        private long[] ownership;
        Entry(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public long[] getBucketEntryData() { return ownership; }
        public void setBucketEntryData(long[] data) { ownership = data; }
        @Override public String toString() { return "(" + x + "," + y + "," + z + ")"; }
    }

    /** Every column's band, or unknown for columns not listed. */
    private static final class Bands implements VisibleWatchersSortingList3D.Bands {
        final Map<Long, Long> bands = new HashMap<>();
        long fallback = VisibleWatchersSortingList3D.UNKNOWN_BAND;
        int queries;
        @Override public long band(int x, int z) {
            queries++;
            return bands.getOrDefault(((long) x << 32) | (z & 0xffffffffL), fallback);
        }
    }

    private static void positions(SpatialWatchersSortingList<?> list, long... positions) throws Exception {
        Field field = SpatialWatchersSortingList.class.getDeclaredField("playerPositions");
        field.setAccessible(true);
        field.set(list, positions);
    }

    private static List<Entry> order(VisibleWatchersSortingList3D<Entry> list) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : list) result.add(entry);
        return result;
    }

    private static VisibleWatchersSortingList3D<Entry> list(Bands bands, long... players) throws Exception {
        VisibleWatchersSortingList3D<Entry> list = new VisibleWatchersSortingList3D<>(0, Collections::emptyList, bands);
        positions(list, players);
        return list;
    }

    @Test public void unknownBandsKeepDistanceOrderBeyondTheNearField() throws Exception {
        VisibleWatchersSortingList3D<Entry> list = list(new Bands(), 0, 0, 0);
        Entry near = new Entry(2, -2, 1), middle = new Entry(0, -6, 0), far = new Entry(9, 9, 9);
        for (Entry entry : Arrays.asList(far, middle, near)) list.add(entry);
        assertEquals(Arrays.asList(near, middle, far), order(list));
        assertEquals(1, list.visibility(middle));
        assertEquals(1, list.visibility(far));
        assertEquals(0, list.visibility(near));
    }

    @Test public void nearFieldIsExactlyTheFiveCubeNeighbourhood() throws Exception {
        Bands bands = new Bands();
        bands.fallback = VisibleWatchersSortingList3D.band(100, 101);
        VisibleWatchersSortingList3D<Entry> list = list(bands, 0, 0, 0);
        int near = 0;
        for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
            if (list.visibility(new Entry(x, y, z)) == 0) near++;
        assertEquals(125, near);
    }

    @Test public void surfaceThenTheWayUpThenHidden() throws Exception {
        Bands bands = new Bands();
        bands.fallback = VisibleWatchersSortingList3D.band(4, 6);
        // A player underground at cube Y -10.
        VisibleWatchersSortingList3D<Entry> list = list(bands, 0, -10, 0);
        Entry surface = new Entry(8, 5, 0), level = new Entry(6, -10, 0), wayUp = new Entry(8, 0, 0);
        Entry below = new Entry(3, -14, 0), sky = new Entry(4, 9, 0);
        assertEquals(1, list.visibility(surface));
        assertEquals(2, list.visibility(level));
        assertEquals(2, list.visibility(wayUp));
        assertEquals(3, list.visibility(below));
        assertEquals(3, list.visibility(sky));
        for (Entry entry : Arrays.asList(below, sky, wayUp, level, surface)) list.add(entry);
        List<Entry> order = order(list);
        assertEquals(surface, order.get(0));
        assertEquals(new java.util.HashSet<>(Arrays.asList(level, wayUp)), new java.util.HashSet<>(order.subList(1, 3)));
        assertEquals(new java.util.HashSet<>(Arrays.asList(below, sky)), new java.util.HashSet<>(order.subList(3, 5)));
    }

    @Test public void aPlayerAboveTheSurfaceSeesTheWayDown() throws Exception {
        Bands bands = new Bands();
        bands.fallback = VisibleWatchersSortingList3D.band(4, 6);
        VisibleWatchersSortingList3D<Entry> list = list(bands, 0, 12, 0);
        assertEquals(2, list.visibility(new Entry(5, 9, 0)));
        assertEquals(2, list.visibility(new Entry(5, 12, 0)));
        assertEquals(3, list.visibility(new Entry(5, 13, 0)));
        assertEquals(3, list.visibility(new Entry(5, 2, 0)));
    }

    @Test public void theBestClassOverAllPlayersWins() throws Exception {
        Bands bands = new Bands();
        bands.fallback = VisibleWatchersSortingList3D.band(4, 6);
        VisibleWatchersSortingList3D<Entry> list = list(bands, 0, -20, 0, 40, -2, 0);
        // Hidden for the first player, on the second one's way up.
        assertEquals(2, list.visibility(new Entry(30, 0, 0)));
        assertEquals(0, list.visibility(new Entry(41, -1, 1)));
    }

    @Test public void noPlayersRankEverythingAsSurfaceWithoutQueries() throws Exception {
        Bands bands = new Bands();
        VisibleWatchersSortingList3D<Entry> list = list(bands);
        assertEquals(1, list.visibility(new Entry(0, 0, 0)));
        assertEquals(0, bands.queries);
    }

    @Test public void oldHiddenEntriesCatchUpOneAtATime() throws Exception {
        Bands bands = new Bands();
        bands.fallback = VisibleWatchersSortingList3D.band(4, 6);
        VisibleWatchersSortingList3D<Entry> list = list(bands, 0, 5, 0);
        Entry hiddenA = new Entry(3, -12, 0), hiddenB = new Entry(4, -12, 0);
        list.add(hiddenA); list.add(hiddenB);
        List<Entry> surface = new ArrayList<>();
        for (int x = 0; x < 50; x++) { Entry e = new Entry(x - 25, 5, 9); surface.add(e); list.add(e); }
        for (int t = 0; t < VisibleWatchersSortingList3D.CATCH_UP_AGE - 1; t++) list.age();
        assertEquals(0, list.promotedCount());
        List<Entry> before = order(list);
        assertTrue(before.indexOf(hiddenA) > 49 && before.indexOf(hiddenB) > 49);
        list.age();
        assertEquals(1, list.promotedCount());
        Entry first = order(list).get(0);
        assertTrue(first == hiddenA || first == hiddenB);
        for (int t = 1; t < VisibleWatchersSortingList3D.CATCH_UP_PERIOD; t++) list.age();
        assertEquals("At most one promotion per period", 1, list.promotedCount());
        list.age();
        assertEquals(2, list.promotedCount());
        List<Entry> after = order(list);
        assertEquals(new java.util.HashSet<>(Arrays.asList(hiddenA, hiddenB)), new java.util.HashSet<>(after.subList(0, 2)));
        list.remove(hiddenA);
        assertEquals(1, list.promotedCount());
        assertEquals(51, order(list).size());
    }

    @Test public void nearEntriesStayAheadOfCatchUp() throws Exception {
        Bands bands = new Bands();
        bands.fallback = VisibleWatchersSortingList3D.band(4, 6);
        VisibleWatchersSortingList3D<Entry> list = list(bands, 0, 5, 0);
        Entry hidden = new Entry(6, -12, 0), near = new Entry(1, 5, 1);
        list.add(hidden);
        for (int t = 0; t < VisibleWatchersSortingList3D.CATCH_UP_AGE; t++) list.age();
        list.add(near);
        assertEquals(Arrays.asList(near, hidden), order(list));
    }

    @Test public void reAddingKeepsTheOriginalWaitingTime() throws Exception {
        Bands bands = new Bands();
        bands.fallback = VisibleWatchersSortingList3D.band(4, 6);
        VisibleWatchersSortingList3D<Entry> list = list(bands, 0, 5, 0);
        Entry hidden = new Entry(6, -12, 0);
        list.add(hidden);
        for (int t = 0; t < 100; t++) list.age();
        list.add(hidden);
        for (int t = 0; t < 100; t++) list.age();
        assertEquals(1, list.promotedCount());
        list.remove(hidden);
        list.add(hidden);
        for (int t = 0; t < 199; t++) list.age();
        assertEquals("A removed entry starts waiting afresh", 0, list.promotedCount());
    }
}
