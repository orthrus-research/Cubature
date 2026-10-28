/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.api.worldgen;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.biome.Biome;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestCubePrimerBiomes {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    // A cube's four biome rows are independent: a generator that varies biomes with height (cave biomes under a
    // surface biome) keeps every row, not only the last one set at that X and Z.
    @Test public void everyFourBlockSectionKeepsItsOwnBiome() {
        Biome[] rows = {Biomes.PLAINS, Biomes.DESERT, Biomes.OCEAN, Biomes.HELL};
        CubePrimer primer = new CubePrimer();
        for (int y = 0; y < 4; y++) {
            primer.setBiome(1, y, 2, rows[y]);
        }
        primer.setBiome(2, 3, 1, Biomes.FOREST);
        for (int y = 0; y < 4; y++) {
            assertSame(rows[y], primer.getBiome(1, y, 2));
            assertNull(primer.getBiome(2, y, 2));
        }
        assertSame(Biomes.FOREST, primer.getBiome(2, 3, 1));
        assertNull(primer.getBiome(2, 0, 1));
    }
}
