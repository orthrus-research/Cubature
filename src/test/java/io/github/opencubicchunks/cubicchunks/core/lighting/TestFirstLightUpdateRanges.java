package io.github.opencubicchunks.cubicchunks.core.lighting;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class TestFirstLightUpdateRanges {
    @Test public void emptyAndReversedRangesCannotExtendTheScan() {
        FirstLightUpdateRanges ranges = new FirstLightUpdateRanges();
        assertTrue(ranges.isEmpty());
        ranges.set(1, 2, 1000, -1000);
        assertTrue(ranges.isEmpty());
        ranges.set(3, 4, -17, -16);
        assertFalse(ranges.isEmpty());
        assertEquals(-17, ranges.minimum);
        assertEquals(-16, ranges.maximum);
        assertEquals(Integer.MAX_VALUE, (int) (ranges.get(15, 15) >> 32));
        assertEquals(Integer.MIN_VALUE, (int) ranges.get(15, 15));
    }

    @Test public void signedEndpointsRoundTripWithoutCoordinateAliasing() {
        FirstLightUpdateRanges ranges = new FirstLightUpdateRanges();
        int[] endpoints = {Integer.MIN_VALUE, -129, -128, -17, -16, -1, 0, 15, 16, 383, 384, Integer.MAX_VALUE};
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            ranges.set(x, z, endpoints[x], endpoints[z]);
        }
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            assertEquals(endpoints[x], (int) (ranges.get(x, z) >> 32));
            assertEquals(endpoints[z], (int) ranges.get(x, z));
        }
    }

    @Test public void restrictedTraversalPreservesTheExactOrderedUpdateCandidates() {
        Random random = new Random(0x1a12c99);
        for (int trial = 0; trial < 100; trial++) {
            FirstLightUpdateRanges ranges = new FirstLightUpdateRanges();
            int[] min = new int[256], max = new int[256];
            int sourceY = random.nextInt(65) - 32;
            for (int i = 0; i < 256; i++) {
                min[i] = random.nextInt(1025) - 512;
                max[i] = random.nextInt(1025) - 512;
                if (trial == 0) { min[i] = Integer.MAX_VALUE; max[i] = Integer.MIN_VALUE; }
                if (trial == 1) { min[i] = Integer.MIN_VALUE; max[i] = Integer.MAX_VALUE; }
                ranges.set(i >> 4, i & 15, min[i], max[i]);
            }
            List<Integer> expected = new ArrayList<>(), actual = new ArrayList<>();
            for (int y = 32; y >= -32; y--) {
                boolean lit = y == sourceY || (y & 3) != 0;
                for (int i = 0; i < 256; i++) {
                    boolean affected = min[i] <= max[i] && min[i] <= y * 16 + 15 && max[i] >= y * 16;
                    if (affected && lit) expected.add((y + 32) * 256 + i);
                }
                if (ranges.isEmpty() || y < (ranges.minimum >> 4) || y > (ranges.maximum >> 4) || !lit) continue;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    long packed = ranges.get(x, z);
                    int a = (int) (packed >> 32), b = (int) packed;
                    if (a <= b && a <= y * 16 + 15 && b >= y * 16) actual.add((y + 32) * 256 + (x << 4) + z);
                }
            }
            assertEquals("Traversal differs at trial " + trial, expected, actual);
        }
    }
}
