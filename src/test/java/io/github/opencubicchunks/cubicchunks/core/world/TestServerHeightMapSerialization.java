/*
 * This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 * Copyright (c) 2015-2021 OpenCubicChunks and contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.world;

import com.sun.management.ThreadMXBean;
import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestServerHeightMapSerialization {
    private static volatile byte[] diagnosticResult;

    @Test public void emptyHeightmapPreservesIts2560ByteFormat() throws IOException {
        byte[] serialized = new ServerHeightMap(new int[256]).getData();
        assertEquals(2560, serialized.length);
        assertArrayEquals(expectedBytes(new HashMap<>()), serialized);
    }

    @Test public void negativeAndHighCavesPreserveExactSegmentBytes() throws IOException {
        Map<Integer, int[]> expected = new HashMap<>();
        // Each fixture entry contains ymin, ymax, then opaque/transparent segment starts.
        expected.put(0, new int[] {-120, 401, -120, -118, -117, -115, 20, 24, 400});
        expected.put((7 << 4) | 4, new int[] {256, 272, 256, 260, 264, 267, 269});
        expected.put(255, new int[] {-10, 10, -10, -2, 3});
        assertArrayEquals(expectedBytes(expected), irregularFixture().getData());
    }

    @Test public void removedSegmentsDoNotSerializeUnusedArrayCapacity() throws ReflectiveOperationException, IOException {
        ServerHeightMap map = new ServerHeightMap(new int[256]);
        for (int y = 0; y <= 16; y += 4) {
            map.onOpacityChange(0, y, 0, 255);
        }
        map.onOpacityChange(0, 8, 0, 0);
        Field field = ServerHeightMap.class.getDeclaredField("segments");
        field.setAccessible(true);
        int[] backing = ((int[][]) field.get(map))[0];
        assertTrue("Fixture must retain sentinel capacity after segment removal", backing.length > 7);
        Map<Integer, int[]> expected = new HashMap<>();
        expected.put(0, new int[] {0, 16, 0, 1, 4, 5, 12, 13, 16});
        byte[] serialized = map.getData();
        assertEquals(2560 + 7 * Integer.BYTES, serialized.length);
        assertArrayEquals(expectedBytes(expected), serialized);
    }

    @Test public void denseCaveFixtureMatchesTheUnchangedStreamWriter() throws Throwable {
        ServerHeightMap map = denseCavesFixture();
        assertArrayEquals(new LegacySerializer(map).getData(), map.getData());
    }

    @Test public void reusingReaderClearsOldSegmentsAndLowestHeightCache() throws IOException {
        ServerHeightMap reused = new ServerHeightMap(new int[256]);
        ServerHeightMap flat = new ServerHeightMap(new int[256]);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            reused.onOpacityChange(x, 0, z, 255);
            reused.onOpacityChange(x, 10, z, 255);
            changeRange(flat, x, z, 0, 20, 255);
        }
        assertEquals(10, reused.getLowestTopBlockY());
        reused.readData(flat.getData());
        assertEquals(20, reused.getLowestTopBlockY());
        assertEquals(4, reused.getTopBlockYBelow(0, 0, 5));
        assertArrayEquals(flat.getData(), reused.getData());
        byte[] empty = new ServerHeightMap(new int[256]).getData();
        reused.readData(empty);
        assertArrayEquals(empty, reused.getData());
        reused.onOpacityChange(0, -80, 0, 255);
        assertEquals(-80, reused.getTopBlockY(0, 0));
    }

    @Test public void unsignedSegmentCountsRetainTheirExactFormat() throws IOException {
        int count = 32769;
        int[] column = new int[count + 2];
        column[1] = count - 1;
        for (int j = 0; j < count; j++) column[j + 2] = j;
        Map<Integer, int[]> columns = new HashMap<>();
        columns.put(0, column);
        byte[] bytes = expectedBytes(columns);
        ServerHeightMap map = new ServerHeightMap(new int[256]);
        map.readData(bytes);
        assertArrayEquals(bytes, map.getData());
        assertEquals(count - 3, map.getTopBlockYBelow(0, 0, count - 1));
        map.onOpacityChange(0, count - 1, 0, 0);
        assertEquals(count - 3, map.getTopBlockY(0, 0));
    }

    @Test public void singleStoredBoundaryCanReturnToAnEmptyColumn() throws IOException {
        Map<Integer, int[]> columns = new HashMap<>();
        columns.put(0, new int[] {1, 3, 1});
        ServerHeightMap map = new ServerHeightMap(new int[256]);
        map.readData(expectedBytes(columns));
        for (int y = 3; y >= 1; y--) map.onOpacityChange(0, y, 0, 0);
        assertEquals(Coords.NO_HEIGHT, map.getTopBlockY(0, 0));
        assertArrayEquals(new ServerHeightMap(new int[256]).getData(), map.getData());
    }

    private static byte[] expectedBytes(Map<Integer, int[]> columns) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (int i = 0; i < 256; i++) {
            int[] column = columns.get(i);
            if (column == null) {
                out.writeInt(Coords.NO_HEIGHT);
                out.writeInt(Coords.NO_HEIGHT);
                out.writeShort(0);
            } else {
                out.writeInt(column[0]);
                out.writeInt(column[1]);
                out.writeShort(column.length - 2);
                for (int j = 2; j < column.length; j++) {
                    out.writeInt(column[j]);
                }
            }
        }
        out.close();
        return bytes.toByteArray();
    }

    private static ServerHeightMap irregularFixture() {
        ServerHeightMap map = new ServerHeightMap(new int[256]);
        changeRange(map, 0, 0, -120, -116, 255);
        changeRange(map, 0, 0, 20, 23, 255);
        changeRange(map, 0, 0, 400, 401, 255);
        map.onOpacityChange(0, -118, 0, 0);
        changeRange(map, 4, 7, 256, 272, 255);
        changeRange(map, 4, 7, 260, 263, 0);
        changeRange(map, 4, 7, 267, 268, 0);
        changeRange(map, 15, 15, -10, 10, 255);
        changeRange(map, 15, 15, -2, 2, 0);
        return map;
    }

    private static ServerHeightMap denseCavesFixture() {
        ServerHeightMap map = new ServerHeightMap(new int[256]);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                changeRange(map, x, z, -64, 160, 255);
                for (int y = -48; y <= 128; y++) {
                    if (Math.floorMod(y + x + z, 17) < 5) {
                        map.onOpacityChange(x, y, z, 0);
                    }
                }
                map.onOpacityChange(x, 256 + z * 16, z, 255);
            }
        }
        return map;
    }

    private static void changeRange(ServerHeightMap map, int x, int z, int minY, int maxY, int opacity) {
        for (int y = minY; y <= maxY; y++) {
            map.onOpacityChange(x, y, z, opacity);
        }
    }

    @FunctionalInterface
    private interface Serializer {
        byte[] getData() throws Throwable;
    }

    /** Reproduce the historical getData buffer policy using the unchanged production writer. */
    private static final class LegacySerializer implements Serializer {
        private final MethodHandle writeData;

        LegacySerializer(ServerHeightMap map) throws ReflectiveOperationException {
            Method method = ServerHeightMap.class.getDeclaredMethod("writeData", DataOutputStream.class);
            method.setAccessible(true);
            writeData = MethodHandles.lookup().unreflect(method).bindTo(map);
        }

        @Override public byte[] getData() throws Throwable {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeData.invokeExact(out);
            out.close();
            return bytes.toByteArray();
        }
    }

    /** Synthetic allocation diagnostic only; arguments are iterations, warmup iterations, sample count. */
    public static void main(String[] args) throws Throwable {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
        int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        if (iterations <= 0 || warmup < 0 || samples <= 0) {
            throw new IllegalArgumentException("Iterations/samples must be positive and warmup nonnegative");
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("This JVM does not support thread allocation measurement");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.printf(Locale.ROOT,
                "{\"diagnostic\":\"heightmap_serialization\",\"java\":\"%s\",\"iterations\":%d,\"warmup\":%d,\"samples\":%d}%n",
                System.getProperty("java.version"), iterations, warmup, samples);
        diagnose("empty", new ServerHeightMap(new int[256]), iterations, warmup, samples, bean);
        diagnose("irregular_caves", irregularFixture(), iterations, warmup, samples, bean);
        diagnose("dense_caves", denseCavesFixture(), iterations, warmup, samples, bean);
    }

    private static void diagnose(String fixture, ServerHeightMap map, int iterations, int warmup, int samples, ThreadMXBean bean)
            throws Throwable {
        Serializer legacy = new LegacySerializer(map), current = map::getData;
        byte[] expected = legacy.getData();
        if (!Arrays.equals(expected, current.getData())) {
            throw new AssertionError("Serialized bytes differ for " + fixture);
        }
        for (int i = 0; i < warmup; i++) {
            diagnosticResult = legacy.getData();
            diagnosticResult = current.getData();
        }
        for (int sample = 0; sample < samples; sample++) {
            // Alternate order to make warmup/order effects visible in the raw samples.
            if ((sample & 1) == 0) {
                measure(fixture, "legacy_default32", sample, legacy, expected.length, iterations, bean);
                measure(fixture, "current_presized", sample, current, expected.length, iterations, bean);
            } else {
                measure(fixture, "current_presized", sample, current, expected.length, iterations, bean);
                measure(fixture, "legacy_default32", sample, legacy, expected.length, iterations, bean);
            }
        }
    }

    private static void measure(String fixture, String mode, int sample, Serializer serializer, int serializedBytes,
            int iterations, ThreadMXBean bean) throws Throwable {
        long thread = Thread.currentThread().getId();
        long allocatedBefore = bean.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            // The published array escapes so the result allocation cannot be optimized away.
            diagnosticResult = serializer.getData();
        }
        long elapsed = System.nanoTime() - started;
        long allocated = bean.getThreadAllocatedBytes(thread) - allocatedBefore;
        System.out.printf(Locale.ROOT,
                "{\"fixture\":\"%s\",\"mode\":\"%s\",\"sample\":%d,\"serialized_bytes\":%d,\"iterations\":%d,"
                        + "\"thread_allocated_bytes\":%d,\"elapsed_ns\":%d}%n",
                fixture, mode, sample, serializedBytes, iterations, allocated, elapsed);
    }
}
