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
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.BlockStatePaletteRegistry;
import net.minecraft.world.chunk.IBlockStatePalette;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * First light asks a block state for its light without the world where the state's block answers from the state
 * alone, and skips a cube whose palette holds no state giving light; this holds both to giving exactly what the
 * world-aware question gives.
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

    // The registered states other than air that give no light wherever they stand, in the game's numbering.
    private static List<IBlockState> darkStates() {
        List<IBlockState> dark = new ArrayList<>();
        for (IBlockState state : Block.BLOCK_STATE_IDS) {
            if (state.getBlock() != Blocks.AIR && FirstLightProcessor.lightFromState(state) && state.getLightValue() == 0) {
                dark.add(state);
            }
        }
        return dark;
    }

    // A container filled block by block numbers and grows its palette as a cube's storage does; the palette is read
    // by its type, since Mixin accessors are not applied here.
    private static BlockStateContainer filled(List<IBlockState> states) {
        BlockStateContainer container = new BlockStateContainer();
        for (int i = 0; i < states.size(); i++) {
            container.set(i & 15, i >> 8, (i >> 4) & 15, states.get(i));
        }
        return container;
    }

    private static IBlockStatePalette paletteOf(BlockStateContainer container) throws ReflectiveOperationException {
        for (Field field : BlockStateContainer.class.getDeclaredFields()) {
            if (field.getType() == IBlockStatePalette.class && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return (IBlockStatePalette) field.get(container);
            }
        }
        throw new AssertionError("no palette in BlockStateContainer");
    }

    @Test public void aLinearPaletteOfDarkStatesIsDarkUntilALightJoinsIt() throws ReflectiveOperationException {
        List<IBlockState> states = new ArrayList<>(Arrays.asList(Blocks.STONE.getDefaultState(),
                Blocks.GRAVEL.getDefaultState(), Blocks.WATER.getDefaultState(), Blocks.STONE.getDefaultState()));
        IBlockStatePalette palette = paletteOf(filled(states));
        assertTrue(palette instanceof BlockStatePaletteLinear);
        assertTrue(FirstLightProcessor.paletteGivesNoLight(palette));
        states.add(Blocks.LAVA.getDefaultState());
        assertFalse(FirstLightProcessor.paletteGivesNoLight(paletteOf(filled(states))));
    }

    // Read to its last state: a light numbered last is found, so no state is passed over.
    @Test public void aHashMapPaletteIsReadToItsLastState() throws ReflectiveOperationException {
        List<IBlockState> states = new ArrayList<>(darkStates().subList(0, 254));
        IBlockStatePalette palette = paletteOf(filled(states));
        assertTrue(palette instanceof BlockStatePaletteHashMap);
        assertTrue(FirstLightProcessor.paletteGivesNoLight(palette));
        states.add(Blocks.GLOWSTONE.getDefaultState());
        palette = paletteOf(filled(states));
        assertTrue(palette instanceof BlockStatePaletteHashMap);
        assertEquals(255, palette.idFor(Blocks.GLOWSTONE.getDefaultState()));
        assertFalse(FirstLightProcessor.paletteGivesNoLight(palette));
    }

    // The registry palette names every state there is, so it is never read: the storage is scanned.
    @Test public void theRegistryPaletteIsNotRead() throws ReflectiveOperationException {
        IBlockStatePalette palette = paletteOf(filled(darkStates().subList(0, 300)));
        assertTrue(palette instanceof BlockStatePaletteRegistry);
        assertFalse(FirstLightProcessor.paletteGivesNoLight(palette));
    }

    // A state asked with the world may give light at some places, so a palette holding one is not dark.
    @Test public void aPaletteHoldingAStateAnsweringFromTheWorldIsNotDark() throws ReflectiveOperationException {
        Block worldly = new Block(Material.ROCK) {
            @Override public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
                return 0;
            }
        };
        IBlockStatePalette palette = paletteOf(filled(Arrays.asList(Blocks.STONE.getDefaultState(), worldly.getDefaultState())));
        assertFalse(FirstLightProcessor.paletteGivesNoLight(palette));
    }
}
