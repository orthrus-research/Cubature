package io.github.opencubicchunks.cubicchunks.core.util;

import io.github.opencubicchunks.cubicchunks.api.util.XYZAddressable;
import io.github.opencubicchunks.cubicchunks.api.util.XZAddressable;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import static org.junit.Assert.*;

public class TestSpatialWatchersSortingList {
    private static final class Entry implements BucketSorterEntry, XYZAddressable, XZAddressable {
        final int x, y, z;
        private long[] ownership;
        Entry(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public long[] getBucketEntryData() { return ownership; }
        public void setBucketEntryData(long[] data) { ownership = data; }
    }

    // Supply the captured coordinates directly so these collection tests need no Minecraft world/player bootstrap.
    private static void positions(SpatialWatchersSortingList<?> list, long... positions) throws Exception {
        Field field = SpatialWatchersSortingList.class.getDeclaredField("playerPositions");
        field.setAccessible(true);
        field.set(list, positions);
    }

    private static <T extends BucketSorterEntry> List<T> entries(Iterable<T> list) {
        List<T> result = new ArrayList<>();
        for (T entry : list) result.add(entry);
        assertEquals("An entry appeared twice", result.size(), new HashSet<>(result).size());
        return result;
    }

    @Test public void emptyMembershipDuplicateAndRepeatedRemoval() {
        WatchersSortingList2D<Entry> list = new WatchersSortingList2D<>(0, Collections::emptyList);
        Entry entry = new Entry(0, 0, 0);
        assertTrue(list.isEmpty());
        list.add(entry); list.add(entry);
        assertFalse(list.isEmpty()); assertEquals(Collections.singletonList(entry), entries(list));
        list.remove(entry); list.remove(entry);
        assertTrue(list.isEmpty()); assertFalse(list.contains(entry)); assertEquals(0, entry.getSorterStorage(0));
    }

    @Test public void independentIntrusiveCollectionIds() {
        Entry entry = new Entry(0, 0, 0);
        WatchersSortingList2D<Entry> columns = new WatchersSortingList2D<>(0, Collections::emptyList);
        WatchersSortingList3D<Entry> cubes = new WatchersSortingList3D<>(1, Collections::emptyList);
        columns.add(entry); cubes.add(entry); columns.remove(entry);
        assertFalse(columns.contains(entry)); assertTrue(cubes.contains(entry));
        assertEquals(Collections.singletonList(entry), entries(cubes));
    }

    @Test public void projectionNearestPlayerAndDistanceLimit() throws Exception {
        WatchersSortingList2D<Entry> columns = new WatchersSortingList2D<>(0, Collections::emptyList);
        WatchersSortingList3D<Entry> cubes = new WatchersSortingList3D<>(1, Collections::emptyList);
        positions(columns, 0, 0, 10, 10); positions(cubes, 0, 0, 0, 10, 4, 10);
        Entry above = new Entry(0, 20, 0), nearestSecond = new Entry(10, 4, 10), distant = new Entry(1000, 1000, 1000);
        for (Entry entry : Arrays.asList(above, nearestSecond, distant)) { columns.add(entry); cubes.add(entry); }
        List<Entry> columnNear = new ArrayList<>(), cubeNear = new ArrayList<>();
        columns.iteratorUpToDistance(0).forEachRemaining(columnNear::add);
        cubes.iteratorUpToDistance(0).forEachRemaining(cubeNear::add);
        assertEquals(new HashSet<>(Arrays.asList(above, nearestSecond)), new HashSet<>(columnNear));
        assertEquals(Collections.singletonList(nearestSecond), cubeNear);
        assertEquals(3, entries(columns).size()); assertEquals(3, entries(cubes).size());
        assertFalse(columns.iteratorUpToDistance(-1).hasNext());
        assertEquals(3, count(columns.iteratorUpToDistance(Integer.MAX_VALUE)));
    }

    private static int count(Iterator<?> iterator) {
        int count = 0;
        while (iterator.hasNext()) { iterator.next(); count++; }
        return count;
    }

    @Test public void iteratorRemovalAfterHasNextCrossesBuckets() throws Exception {
        WatchersSortingList3D<Entry> list = new WatchersSortingList3D<>(0, Collections::emptyList);
        positions(list, 0, 0, 0);
        Entry first = new Entry(0, 0, 0), second = new Entry(1, 0, 0);
        list.add(first); list.add(second);
        Iterator<Entry> iterator = list.iterator();
        assertSame(first, iterator.next()); assertTrue(iterator.hasNext()); iterator.remove();
        assertFalse(list.contains(first)); assertTrue(list.contains(second));
        assertSame(second, iterator.next()); assertFalse(iterator.hasNext()); iterator.remove();
        assertTrue(list.isEmpty());
    }

    @Test public void removeIfVisitsEverySwapReplacement() {
        WatchersSortingList3D<Entry> list = new WatchersSortingList3D<>(0, Collections::emptyList);
        for (int i = 0; i < 127; i++) list.add(new Entry(i, 0, 0));
        list.removeIf(entry -> entry.x % 2 == 0);
        assertEquals(63, entries(list).size());
        for (Entry entry : list) assertEquals(1, entry.x % 2);
        list.removeIf(entry -> true); assertTrue(list.isEmpty());
    }

    @Test public void iteratorExhaustionAndRemovalStateFollowContract() {
        WatchersSortingList2D<Entry> list = new WatchersSortingList2D<>(0, Collections::emptyList);
        Iterator<Entry> empty = list.iterator();
        try { empty.next(); fail(); } catch (NoSuchElementException expected) { }
        try { empty.remove(); fail(); } catch (IllegalStateException expected) { }
        list.add(new Entry(0, 0, 0)); Iterator<Entry> one = list.iterator(); one.next(); one.remove();
        try { one.remove(); fail(); } catch (IllegalStateException expected) { }
    }

    @Test public void redistributionPreservesEveryOwnerAndVisitsReplacements() throws Exception {
        WatchersSortingList3D<Entry> list = new WatchersSortingList3D<>(0, Collections::emptyList);
        List<Entry> expected = new ArrayList<>();
        for (int i = 0; i < 300; i++) { Entry entry = new Entry(i % 20, i / 20, 0); expected.add(entry); list.add(entry); }
        positions(list, 0, 0, 0);
        list.redistributeBucket(110); // No-player entries initially occupy floor(64 * sqrt(3)).
        assertEquals(new HashSet<>(expected), new HashSet<>(entries(list)));
        assertEquals(1, count(list.iteratorUpToDistance(0)));
        for (Entry entry : expected) assertTrue(list.contains(entry));
        // A normal tick with an empty player set eventually moves every entry back to the final bucket.
        for (int i = 0; i < 24; i++) list.tick();
        assertEquals(new HashSet<>(expected), new HashSet<>(entries(list)));
        assertFalse(list.iteratorUpToDistance(109).hasNext());
    }

    @Test public void randomizedMembershipAcrossMovesAndRemovals() throws Exception {
        WatchersSortingList2D<Entry> list = new WatchersSortingList2D<>(0, Collections::emptyList);
        List<Entry> all = new ArrayList<>();
        HashSet<Entry> expected = new HashSet<>(); Random random = new Random(261212);
        for (int i = 0; i < 200; i++) all.add(new Entry(random.nextInt(401) - 200, 0, random.nextInt(401) - 200));
        for (int i = 0; i < 2000; i++) {
            Entry entry = all.get(random.nextInt(all.size()));
            if (random.nextBoolean()) { list.add(entry); expected.add(entry); }
            else { list.remove(entry); expected.remove(entry); }
            if (i % 100 == 0) {
                positions(list, random.nextInt(100), random.nextInt(100));
                for (int bucket = 0; bucket < 91; bucket++) list.redistributeBucket(bucket);
                assertEquals(expected, new HashSet<>(entries(list)));
                assertEquals(expected.isEmpty(), list.isEmpty());
            }
        }
    }
}
