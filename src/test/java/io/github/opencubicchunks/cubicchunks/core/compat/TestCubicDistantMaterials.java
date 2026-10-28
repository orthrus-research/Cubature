package io.github.opencubicchunks.cubicchunks.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.CubicDistantMaterials;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

/** 1.12 blocks DH 3.3.3 calls UNKNOWN get the shader material their Minecraft material implies. */
public class TestCubicDistantMaterials {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void vanillaTerrain() {
        assertEquals("GRASS", CubicDistantMaterials.name(Blocks.GRASS.getDefaultState()));
        assertEquals("DIRT", CubicDistantMaterials.name(Blocks.DIRT.getDefaultState()));
        assertEquals("STONE", CubicDistantMaterials.name(Blocks.STONE.getDefaultState()));
        assertEquals("SAND", CubicDistantMaterials.name(Blocks.GRAVEL.getDefaultState()));
        assertEquals("SNOW", CubicDistantMaterials.name(Blocks.SNOW.getDefaultState()));
    }

    @Test public void vegetation() {
        assertEquals("LEAVES", CubicDistantMaterials.name(Blocks.LEAVES.getDefaultState()));
        assertEquals("LEAVES", CubicDistantMaterials.name(Blocks.TALLGRASS.getDefaultState()));
        assertEquals("WOOD", CubicDistantMaterials.name(Blocks.LOG.getDefaultState()));
    }

    @Test public void unclassifiedStaysUnknown() {
        assertNull(CubicDistantMaterials.name(Blocks.GLASS.getDefaultState()));
        assertNull(CubicDistantMaterials.name(null));
    }
}
