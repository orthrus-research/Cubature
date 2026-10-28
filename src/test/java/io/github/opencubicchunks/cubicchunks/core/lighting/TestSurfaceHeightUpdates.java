package io.github.opencubicchunks.cubicchunks.core.lighting;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestSurfaceHeightUpdates {
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    @Test public void keepsEveryLegacyColumnAtNegativeAndHighCoordinatesWithoutDuplicates() {
        for (CubePos position : new CubePos[] {new CubePos(0, 0, 0), new CubePos(-13, -8, -17), new CubePos(128, 23, 128)}) {
            Set<Long> legacy = new HashSet<>(), actual = new HashSet<>();
            int oldCalls = 0;
            for (BlockPos.MutableBlockPos block : BlockPos.getAllInBoxMutable(position.getMinBlockPos(), position.getMaxBlockPos().add(1, 1, 1))) {
                oldCalls++; legacy.add(key(block.getX(), block.getZ()));
            }
            SurfaceHeightUpdates.forCube(position, (x, z) -> assertTrue("Duplicate column notification", actual.add(key(x, z))));
            assertEquals(4913, oldCalls);
            assertEquals(289, actual.size());
            assertEquals(legacy, actual);
        }
    }

    @Test public void footprintIsIndependentOfCubeHeight() {
        Set<Long> low = new HashSet<>(), high = new HashSet<>();
        SurfaceHeightUpdates.forCube(new CubePos(-1, -8, 2), (x, z) -> low.add(key(x, z)));
        SurfaceHeightUpdates.forCube(new CubePos(-1, 23, 2), (x, z) -> high.add(key(x, z)));
        assertEquals(low, high);
        assertTrue(low.contains(key(0, 48)));
    }
}
