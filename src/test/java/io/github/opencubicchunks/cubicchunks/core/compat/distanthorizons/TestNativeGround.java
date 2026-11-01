package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestNativeGround {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    /** A player at Y 320 with a 12-cube view: cubes 8..32, blocks [128, 528). */
    private static final int[] HIGH = {-1800, -1500, 128, 528};

    @Test public void aViewAboveTheGroundHoldsNothing() {
        assertFalse("The 8 km world's join point: ground at Y 100", NativeGround.holds(HIGH, (x, z) -> 100));
        assertTrue("Ground at Y 200 shows", NativeGround.holds(HIGH, (x, z) -> 200));
        assertFalse("A peak at the view's top shows no air above it", NativeGround.holds(HIGH, (x, z) -> 527));
        assertTrue(NativeGround.holds(HIGH, (x, z) -> 526));
        assertTrue("The bottom block counts", NativeGround.holds(HIGH, (x, z) -> 128));
        assertFalse(NativeGround.holds(HIGH, (x, z) -> 127));
    }

    @Test public void anyViewShowingItsGroundHolds() {
        int[] two = {0, 0, 128, 528, 500, 500, -64, 336};
        assertTrue("The second player stands on its ground", NativeGround.holds(two, (x, z) -> x == 0 ? 100 : 64));
        assertFalse(NativeGround.holds(two, (x, z) -> x == 0 ? 100 : 400));
        assertTrue("No players: held as before", NativeGround.holds(new int[0], (x, z) -> 0));
    }

    @Test public void theTopBlockIsTheHighestRunThatIsNotAir() {
        ICubicTerrainPreview.Sampler sampler = (x, z, consumer) -> {
            consumer.begin(null);
            consumer.addRun(-1664, 90, Blocks.STONE.getDefaultState());
            consumer.addRun(90, 101, Blocks.WATER.getDefaultState());
            consumer.addRun(101, 384, Blocks.AIR.getDefaultState());
        };
        assertEquals("The water's surface", 100, NativeGround.topBlock(sampler, 0, 0, -1664));
        ICubicTerrainPreview.Sampler air = (x, z, consumer) -> consumer.addRun(-1664, 384, Blocks.AIR.getDefaultState());
        assertEquals(-1665, NativeGround.topBlock(air, 0, 0, -1664));
    }
}
