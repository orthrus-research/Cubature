package io.github.opencubicchunks.cubicchunks.api.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public class TestXYZMap {
    private static final class Point implements XYZAddressable {
        final int x, y, z;
        Point(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public int getX() { return x; }
        @Override public int getY() { return y; }
        @Override public int getZ() { return z; }
        String key() { return x + "," + y + "," + z; }
    }

    private static void verify(XYZMap<Point> map, Map<String, Point> expected) {
        assertEquals(expected.size(), map.getSize());
        Set<String> visited = new HashSet<>();
        for (Point point : map) {
            assertNotNull(point);
            assertTrue("Repeated coordinate", visited.add(point.key()));
            assertSame(expected.get(point.key()), point);
            assertSame(point, map.get(point.x, point.y, point.z));
        }
        assertEquals(expected.keySet(), visited);
    }

    @Test public void removesEveryPositionFromAWrappedCollisionCluster() {
        // For zero X/Y, the existing polynomial hash is seed^4 + Z*seed.
        int seed = 1183822147, homeZ = 0;
        while (((seed * seed * seed * seed + homeZ * seed) & 127) != 126) homeZ++;
        for (int victim = 0; victim < 40; victim++) {
            XYZMap<Point> map = new XYZMap<>(0.7f, 128);
            Map<String, Point> expected = new HashMap<>();
            List<Point> order = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                Point point = new Point(0, 0, homeZ + i * 128);
                map.put(point); expected.put(point.key(), point); order.add(point);
            }
            Point removed = order.get(victim);
            assertSame(removed, map.remove(removed));
            expected.remove(removed.key());
            order.set(victim, order.get(order.size() - 1));
            order.remove(order.size() - 1);
            assertNull(map.get(removed.x, removed.y, removed.z));
            verify(map, expected);
            List<Point> actual = new ArrayList<>();
            map.forEach(actual::add);
            assertEquals("Dense last-element swap order", order, actual);
        }
    }

    @Test public void mixedMutationsMatchAnIndependentCoordinateOracle() {
        for (int mode = 0; mode < 4; mode++) {
            Random random = new Random(0x43554245L + mode);
            XYZMap<Point> map = new XYZMap<>(0.7f, 4);
            Map<String, Point> expected = new HashMap<>();
            for (int step = 0; step < 20000; step++) {
                int x = random.nextInt(20) - 10, y = random.nextInt(12) - 6, z = random.nextInt(20) - 10;
                if (mode == 1) { x *= 32; y *= 32; z *= 32; }
                if (mode == 2) { x += 1874980; z -= 1874980; }
                if (mode == 3) { x = random.nextInt(); y = random.nextInt(); z = random.nextInt(); }
                Point point = new Point(x, y, z);
                switch (random.nextInt(5)) {
                    case 0: case 1: assertSame(expected.put(point.key(), point), map.put(point)); break;
                    case 2: assertSame(expected.remove(point.key()), map.remove(x, y, z)); break;
                    case 3: assertSame(expected.get(point.key()), map.get(x, y, z)); break;
                    default: assertEquals(expected.containsKey(point.key()), map.contains(x, y, z));
                }
                if (step % 251 == 0) verify(map, expected);
                if (step % 5000 == 4999) { map.clear(); expected.clear(); verify(map, expected); }
            }
        }
    }

    @Test public void iteratorRemovalVisitsSwappedEntriesOnceAndCanDrainTheMap() {
        XYZMap<Point> map = new XYZMap<>(0.7f, 2048);
        Map<String, Point> expected = new HashMap<>();
        for (int x = -12; x <= 12; x++) for (int y = -8; y < 24; y++) {
            Point point = new Point(x, y, 128);
            map.put(point); expected.put(point.key(), point);
        }
        Set<String> original = new HashSet<>(expected.keySet()), visited = new HashSet<>();
        Iterator<Point> iterator = map.iterator();
        while (iterator.hasNext()) {
            Point point = iterator.next();
            assertTrue(visited.add(point.key()));
            if ((point.y & 1) == 0) { iterator.remove(); expected.remove(point.key()); }
        }
        assertEquals(original, visited);
        verify(map, expected);
        iterator = map.iterator();
        while (iterator.hasNext()) { iterator.next(); iterator.remove(); }
        assertTrue(map.getSize() == 0 && !map.iterator().hasNext());
    }

    @Test public void replacementAfterDeletionPreservesIdentityAndSize() {
        XYZMap<Point> map = new XYZMap<>(0.7f, 32);
        Point first = new Point(-7, -8, 12), second = new Point(-7, -8, 44);
        map.put(first); map.put(second);
        assertSame(first, map.remove(first));
        Point replacement = new Point(second.x, second.y, second.z);
        assertSame(second, map.put(replacement));
        assertEquals(1, map.getSize());
        assertSame(replacement, map.get(second.x, second.y, second.z));
        assertNull(map.remove(first));
        assertSame(replacement, map.remove(replacement));
        assertEquals(0, map.getSize());
    }
}
