/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
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
package io.github.opencubicchunks.cubicchunks.core.world;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;

/**
 * Cube saves keep 20 bits of a block state's id (the Blocks, Data, Add and Add2 arrays), so a state
 * whose id is {@link ICubeSource#MAX_BLOCK_STATES} or more would lose its high bits when a cube is
 * saved. Checked once the registries are complete; a warning, not a refusal.
 */
public final class BlockStateLimit {

    private BlockStateLimit() {
    }

    /** The highest block state id the game has handed out. */
    static int highestId(Iterable<IBlockState> states) {
        int highest = -1;
        for (IBlockState state : states) {
            highest = Math.max(highest, Block.BLOCK_STATE_IDS.get(state));
        }
        return highest;
    }

    /** Whether ids up to and including this one fit in a cube save. */
    static boolean fits(int highestId) {
        return highestId < ICubeSource.MAX_BLOCK_STATES;
    }

    /** Warns when a block state id does not fit in a cube save. */
    public static void check() {
        int highest = highestId(Block.BLOCK_STATE_IDS);
        if (!fits(highest)) {
            CubicChunks.LOGGER.warn("Block state ids reach {}, but cube saves keep {} ids (20 bits): blocks whose state "
                            + "id is {} or more lose the high bits of their id when a cube is saved, and come back as other "
                            + "blocks. Remove mods until the highest id is below {}.",
                    highest, ICubeSource.MAX_BLOCK_STATES, ICubeSource.MAX_BLOCK_STATES, ICubeSource.MAX_BLOCK_STATES);
        }
    }
}
