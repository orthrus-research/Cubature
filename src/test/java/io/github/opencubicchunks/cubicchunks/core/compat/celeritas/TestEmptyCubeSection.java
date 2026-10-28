package io.github.opencubicchunks.cubicchunks.core.compat.celeritas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestEmptyCubeSection {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void aCubeOpenToTheSkyKeepsCeleritasOwnEmptySection() {
        // Cube -1 spans Y -16..-1; every column top lies below it.
        assertNull(EmptyCubeSection.of(-1, (x, z) -> -17));
        assertNull(EmptyCubeSection.of(-1, (x, z) -> Integer.MIN_VALUE));
    }

    @Test public void aBuriedCubeIsDarkNotDaylit() {
        // A cave void under ground at Y 100 in every column: no sky light anywhere in it.
        ExtendedBlockStorage dark = EmptyCubeSection.of(-14, (x, z) -> 100);
        assertTrue(dark.isEmpty());
        for (int i = 0; i < 4096; i++) {
            assertEquals(0, dark.getSkyLight(i & 15, i >> 8, (i >> 4) & 15));
            assertEquals(0, dark.getBlockLight(i & 15, i >> 8, (i >> 4) & 15));
        }
        // A top at the cube's highest block still covers every block of it.
        assertSame(dark, EmptyCubeSection.of(-1, (x, z) -> -1));
    }

    @Test public void aPartlyCoveredCubeFollowsEachColumnsTop() {
        // Under an overhang on the west half (top Y 40), open on the east half; cube 1 spans Y 16..31,
        // and one column's top lies inside the cube, at Y 20.
        ExtendedBlockStorage section = EmptyCubeSection.of(1, (x, z) -> x == 12 && z == 3 ? 20 : x < 8 ? 40 : 0);
        assertTrue(section.isEmpty());
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int expected = x == 12 && z == 3 ? (16 + y > 20 ? 15 : 0) : x < 8 ? 0 : 15;
                    assertEquals("x " + x + " y " + y + " z " + z, expected, section.getSkyLight(x, y, z));
                }
            }
        }
    }
}
