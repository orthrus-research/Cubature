package io.github.opencubicchunks.cubicchunks.core.lighting;

import java.util.Arrays;

/** Per-call scratch: each block column supplies one inclusive range, or remains empty. */
final class FirstLightUpdateRanges {
    private final long[] ranges = new long[256];
    int minimum = Integer.MAX_VALUE, maximum = Integer.MIN_VALUE;

    FirstLightUpdateRanges() {
        Arrays.fill(ranges, pack(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    void set(int x, int z, int min, int max) {
        ranges[(x << 4) | z] = pack(min, max);
        if (min <= max) {
            minimum = Math.min(minimum, min);
            maximum = Math.max(maximum, max);
        }
    }

    long get(int x, int z) { return ranges[(x << 4) | z]; }
    boolean isEmpty() { return minimum > maximum; }
    private static long pack(int min, int max) { return ((long) min << 32) | (max & 0xffffffffL); }
}
