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
package io.github.opencubicchunks.cubicchunks.core.world.change;

import io.github.opencubicchunks.cubicchunks.api.change.ICubeBlockCopy;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import javax.annotation.Nullable;

/** A cube's 4,096 block states, copied from its storage on the server thread. */
public final class CubeBlockCopy implements ICubeBlockCopy {

    private final CubePos pos;
    @Nullable private final IBlockState[] states;

    private CubeBlockCopy(CubePos pos, @Nullable IBlockState[] states) {
        this.pos = pos;
        this.states = states;
    }

    /** Copies the storage; null storage is a cube of air. */
    public static CubeBlockCopy of(CubePos pos, @Nullable ExtendedBlockStorage storage) {
        if (storage == null || storage.isEmpty()) {
            return new CubeBlockCopy(pos, null);
        }
        IBlockState[] states = new IBlockState[16 * 16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    states[(y << 8) | (z << 4) | x] = storage.get(x, y, z);
                }
            }
        }
        return new CubeBlockCopy(pos, states);
    }

    @Override public CubePos pos() {
        return pos;
    }

    @Override public IBlockState getBlockState(int localX, int localY, int localZ) {
        if (states == null) {
            return Blocks.AIR.getDefaultState();
        }
        return states[((localY & 15) << 8) | ((localZ & 15) << 4) | (localX & 15)];
    }
}
