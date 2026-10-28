package io.github.opencubicchunks.cubicchunks.core.visibility;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.util.math.ChunkPos;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestCuboidalCubeSelector {
    private final CuboidalCubeSelector selector = new CuboidalCubeSelector();

    /** Count insertion attempts too: ordinary Set equality would conceal duplicate slab emissions. */
    private static class UniqueSet<T> extends HashSet<T> {
        @Override public boolean add(T value) {
            assertTrue("Duplicate position emitted: " + value, super.add(value));
            return true;
        }
    }

    private static Set<CubePos> cubeDifference(CubePos from, int horizontal, int vertical,
            CubePos excluded, int excludedHorizontal, int excludedVertical) {
        Set<CubePos> result = new HashSet<>();
        for (long x = (long) from.getX() - horizontal; x <= (long) from.getX() + horizontal; x++) {
            for (long y = (long) from.getY() - vertical; y <= (long) from.getY() + vertical; y++) {
                for (long z = (long) from.getZ() - horizontal; z <= (long) from.getZ() + horizontal; z++) {
                    if (Math.abs(x - excluded.getX()) > excludedHorizontal
                            || Math.abs(y - excluded.getY()) > excludedVertical
                            || Math.abs(z - excluded.getZ()) > excludedHorizontal) {
                        result.add(new CubePos((int) x, (int) y, (int) z));
                    }
                }
            }
        }
        return result;
    }

    private static Set<ChunkPos> columnDifference(CubePos from, int horizontal, CubePos excluded, int excludedHorizontal) {
        Set<ChunkPos> result = new HashSet<>();
        for (long x = (long) from.getX() - horizontal; x <= (long) from.getX() + horizontal; x++) {
            for (long z = (long) from.getZ() - horizontal; z <= (long) from.getZ() + horizontal; z++) {
                if (Math.abs(x - excluded.getX()) > excludedHorizontal || Math.abs(z - excluded.getZ()) > excludedHorizontal) {
                    result.add(new ChunkPos((int) x, (int) z));
                }
            }
        }
        return result;
    }

    private void checkMovement(CubePos from, CubePos to, int horizontal, int vertical) {
        Set<CubePos> removed = new UniqueSet<>(), loaded = new UniqueSet<>();
        Set<ChunkPos> removedColumns = new UniqueSet<>(), loadedColumns = new UniqueSet<>();
        selector.findChanged(from, to, horizontal, vertical, removed, loaded, removedColumns, loadedColumns);
        assertEquals(cubeDifference(from, horizontal, vertical, to, horizontal, vertical), removed);
        assertEquals(cubeDifference(to, horizontal, vertical, from, horizontal, vertical), loaded);
        assertEquals(columnDifference(from, horizontal, to, horizontal), removedColumns);
        assertEquals(columnDifference(to, horizontal, from, horizontal), loadedColumns);
        assertTrue(Collections.disjoint(removed, loaded));
        assertTrue(Collections.disjoint(removedColumns, loadedColumns));
    }

    @Test public void smallDiagonalMovesAndStationaryView() {
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
            checkMovement(new CubePos(-16, -8, 16), new CubePos(-16 + x, -8 + y, 16 + z), 12, 12);
        }
    }

    @Test public void overlapBoundariesTeleportsAndNegativeCoordinates() {
        for (int shift : new int[]{-50, -25, -24, -16, 16, 24, 25, 50}) {
            checkMovement(new CubePos(-16, -8, -16), new CubePos(-16 + shift, -8, -16), 12, 12);
            checkMovement(new CubePos(-16, -8, -16), new CubePos(-16, -8 + shift, -16), 12, 12);
            checkMovement(new CubePos(-16, -8, -16), new CubePos(-16 + shift, -8 + shift, -16 + shift), 12, 12);
        }
    }

    @Test public void randomizedMovementAndUnequalRadii() {
        Random random = new Random(261212L);
        for (int i = 0; i < 80; i++) {
            CubePos from = new CubePos(random.nextInt(3750001) - 1875000, random.nextInt(65537) - 32768,
                    random.nextInt(3750001) - 1875000);
            CubePos to = new CubePos(from.getX() + random.nextInt(81) - 40,
                    from.getY() + random.nextInt(81) - 40, from.getZ() + random.nextInt(81) - 40);
            checkMovement(from, to, i % 2 == 0 ? 12 : i % 5, i % 3 == 0 ? 12 : i % 7);
        }
        checkMovement(new CubePos(0, 0, 0), new CubePos(0, 1, 0), 0, 0);
    }

    @Test public void worldBordersAndExtremeRepresentableEndpoints() {
        checkMovement(new CubePos(-1875000, -134217716, 1875000), new CubePos(1875000, 134217715, -1875000), 12, 12);
        checkMovement(new CubePos(Integer.MAX_VALUE - 12, Integer.MIN_VALUE + 12, 0),
                new CubePos(Integer.MAX_VALUE - 13, Integer.MIN_VALUE + 13, 1), 12, 12);
    }

    @Test public void distanceChangesIncludingOneAxisGrowingWhileAnotherShrinks() {
        CubePos center = new CubePos(-17, -8, 31);
        int[][] changes = {{12, 12, 11, 11}, {12, 12, 12, 0}, {12, 12, 0, 12},
                {4, 12, 12, 4}, {12, 4, 4, 12}, {12, 12, 12, 12}, {0, 0, 12, 12}};
        for (int[] change : changes) {
            Set<CubePos> cubes = new UniqueSet<>();
            Set<ChunkPos> columns = new UniqueSet<>();
            selector.findAllUnloadedOnViewDistanceDecrease(center, change[0], change[2], change[1], change[3], cubes, columns);
            assertEquals(cubeDifference(center, change[0], change[1], center, change[2], change[3]), cubes);
            assertEquals(columnDifference(center, change[0], center, change[2]), columns);
        }
    }

    @Test public void callerOwnedOutputSetsRetainExistingEntries() {
        CubePos sentinel = new CubePos(9000, 9000, 9000);
        ChunkPos column = new ChunkPos(9000, 9000);
        Set<CubePos> removed = new HashSet<>(), loaded = new HashSet<>();
        Set<ChunkPos> removedColumns = new HashSet<>(), loadedColumns = new HashSet<>();
        loaded.add(sentinel); loadedColumns.add(column);
        selector.findChanged(new CubePos(0, 0, 0), new CubePos(1, 0, 0), 12, 12, removed, loaded, removedColumns, loadedColumns);
        assertTrue(loaded.contains(sentinel)); assertTrue(loadedColumns.contains(column));
        assertEquals(626, loaded.size()); assertEquals(26, loadedColumns.size());
    }
}
