package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import static org.junit.Assert.assertSame;

import net.minecraft.block.BlockLiquid;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestPreviewStates {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void waterAtAnyLevelIsLitAsSourceWater() {
        assertSame(Blocks.WATER.getDefaultState(), CubicTerrainPreviewGenerator.previewState(Blocks.WATER.getDefaultState()));
        assertSame("A river's flowing water, as a terrain preview emits it",
                Blocks.WATER.getDefaultState(),
                CubicTerrainPreviewGenerator.previewState(Blocks.FLOWING_WATER.getDefaultState().withProperty(BlockLiquid.LEVEL, 1)));
        assertSame(Blocks.WATER.getDefaultState(),
                CubicTerrainPreviewGenerator.previewState(Blocks.WATER.getDefaultState().withProperty(BlockLiquid.LEVEL, 7)));
    }

    @Test public void otherFluidsAndEmittersKeepTheirStateRatherThanFailTheTile() {
        assertSame(Blocks.LAVA.getDefaultState(), CubicTerrainPreviewGenerator.previewState(Blocks.LAVA.getDefaultState()));
        assertSame(Blocks.GLOWSTONE.getDefaultState(), CubicTerrainPreviewGenerator.previewState(Blocks.GLOWSTONE.getDefaultState()));
        assertSame(Blocks.STONE.getDefaultState(), CubicTerrainPreviewGenerator.previewState(Blocks.STONE.getDefaultState()));
    }
}
