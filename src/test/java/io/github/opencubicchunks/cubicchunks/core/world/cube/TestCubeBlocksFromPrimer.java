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
package io.github.opencubicchunks.cubicchunks.core.world.cube;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A cube built from a primer asks a block's material once for each run of one state; this holds the copy to giving
 * exactly the storage the game's per-block copy gives: the same blocks, counts and palette, byte for byte.
 */
public class TestCubeBlocksFromPrimer {

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    // The copy as the primer constructor made it before: every block's material asked of the block.
    @SuppressWarnings("deprecation")
    private static ExtendedBlockStorage perBlock(CubePrimer primer) {
        ExtendedBlockStorage storage = null;
        for (int y = Cube.SIZE - 1; y >= 0; y--) {
            for (int z = 0; z < Cube.SIZE; z++) {
                for (int x = 0; x < Cube.SIZE; x++) {
                    IBlockState state = primer.getBlockState(x, y, z);
                    if (state.getMaterial() != Material.AIR) {
                        if (storage == null) {
                            storage = new ExtendedBlockStorage(0, true);
                        }
                        storage.set(x, y, z, state);
                    }
                }
            }
        }
        return storage;
    }

    private static byte[] bytes(ExtendedBlockStorage storage) {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        storage.getData().write(buffer);
        byte[] out = new byte[buffer.readableBytes()];
        buffer.readBytes(out);
        return out;
    }

    private static void assertCopiesAlike(CubePrimer primer) {
        ExtendedBlockStorage expected = perBlock(primer);
        ExtendedBlockStorage actual = Cube.copyBlocks(primer, () -> new ExtendedBlockStorage(0, true));
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        for (int y = 0; y < Cube.SIZE; y++) {
            for (int z = 0; z < Cube.SIZE; z++) {
                for (int x = 0; x < Cube.SIZE; x++) {
                    assertSame(x + " " + y + " " + z, expected.get(x, y, z), actual.get(x, y, z));
                }
            }
        }
        assertEquals(expected.isEmpty(), actual.isEmpty());
        assertEquals(expected.needsRandomTick(), actual.needsRandomTick());
        assertArrayEquals("the palette and its numbering", bytes(expected), bytes(actual));
    }

    @Test public void anAirPrimerGivesNoStorage() {
        assertCopiesAlike(new CubePrimer());
    }

    // Rock with caves, ores and a lava pool: long runs of one state broken by others.
    @Test public void rockWithCavesOresAndLavaCopiesAlike() {
        CubePrimer primer = new CubePrimer();
        Random random = new Random(8675309);
        for (int y = 0; y < Cube.SIZE; y++) {
            for (int z = 0; z < Cube.SIZE; z++) {
                for (int x = 0; x < Cube.SIZE; x++) {
                    int cave = (x - 8) * (x - 8) + (y - 6) * (y - 6) + (z - 7) * (z - 7);
                    IBlockState state = cave < 20 ? Blocks.AIR.getDefaultState()
                            : y < 3 && x > 10 ? Blocks.LAVA.getDefaultState()
                            : random.nextInt(40) == 0 ? Blocks.IRON_ORE.getDefaultState()
                            : random.nextInt(25) == 0 ? Blocks.GRAVEL.getDefaultState()
                            : Blocks.STONE.getDefaultState();
                    primer.setBlockState(x, y, z, state);
                }
            }
        }
        assertCopiesAlike(primer);
    }

    // Enough states that the palette grows from linear through hash map to the registry, in the order they appear.
    @Test public void aPaletteGrowingThroughEveryKindCopiesAlike() {
        List<IBlockState> states = new ArrayList<>();
        for (IBlockState state : Block.BLOCK_STATE_IDS) {
            states.add(state);
        }
        for (int count : new int[] {3, 17, 200, 300}) {
            CubePrimer primer = new CubePrimer();
            Random random = new Random(count);
            for (int y = 0; y < Cube.SIZE; y++) {
                for (int z = 0; z < Cube.SIZE; z++) {
                    for (int x = 0; x < Cube.SIZE; x++) {
                        int run = (x / 4) + 4 * z + 64 * y;
                        primer.setBlockState(x, y, z, states.get((run * 31 + random.nextInt(2)) % count));
                    }
                }
            }
            assertCopiesAlike(primer);
        }
    }
}
