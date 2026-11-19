/*
 *  This file is part of Cubature, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.lighting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * First light asks a block state for its light without the world where the state's block answers from the state
 * alone; this holds that to giving exactly what the world-aware question gives.
 */
public class TestFirstLightSources {

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    // Every state the game registers whose light is taken from the state gives the same as the world-aware question:
    // the block's own world-aware method is Block's, which asks the state.
    @Test public void everyStateTakenFromItselfGivesWhatTheWorldAwareQuestionGives() {
        int fromState = 0;
        for (IBlockState state : Block.BLOCK_STATE_IDS) {
            if (FirstLightProcessor.lightFromState(state)) {
                fromState++;
                assertEquals(state.toString(), state.getLightValue((IBlockAccess) null, BlockPos.ORIGIN), state.getLightValue());
            }
        }
        assertTrue("most of the game's states give light from themselves", fromState > 1000);
    }

    @Test public void theGamesOwnLightsAndRockAreTakenFromTheirState() {
        assertTrue(FirstLightProcessor.lightFromState(Blocks.STONE.getDefaultState()));
        assertTrue(FirstLightProcessor.lightFromState(Blocks.AIR.getDefaultState()));
        assertTrue(FirstLightProcessor.lightFromState(Blocks.GLOWSTONE.getDefaultState()));
        assertEquals(15, Blocks.GLOWSTONE.getDefaultState().getLightValue());
        assertTrue(FirstLightProcessor.lightFromState(Blocks.LAVA.getDefaultState()));
    }

    // A block that answers its light from the world is asked with the world, never from its state.
    @Test public void aBlockAnsweringFromTheWorldIsAskedWithIt() {
        Block worldly = new Block(Material.ROCK) {
            @Override public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
                return 7;
            }
        };
        assertFalse(FirstLightProcessor.lightFromState(worldly.getDefaultState()));
    }
}
