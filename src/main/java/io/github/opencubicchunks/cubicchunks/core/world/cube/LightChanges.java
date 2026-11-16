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
package io.github.opencubicchunks.cubicchunks.core.world.cube;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Server only: which blocks of a cube already sent to a player had their light written since the
 * cube's light was last sent on its own. One bit a block, 512 bytes, made the first time a sent
 * cube's light is written. Any thread may mark; the server thread drains.
 */
public final class LightChanges {

    private final AtomicLongArray bits = new AtomicLongArray(64);

    /** Marks a block, by its index {@code y << 8 | z << 4 | x}. */
    public void mark(int index) {
        long bit = 1L << (index & 63);
        int word = index >> 6;
        long now = bits.get(word);
        if ((now & bit) == 0) {
            bits.getAndAccumulate(word, bit, (a, b) -> a | b);
        }
    }

    /**
     * Takes the marked blocks and clears them.
     *
     * @param out receives the indices of up to {@code out.length} blocks
     * @return how many blocks were marked; more than {@code out.length} when they did not all fit,
     *         and then {@code out} holds only the first of them
     */
    public int drainTo(short[] out) {
        int count = 0;
        for (int word = 0; word < 64; word++) {
            long taken = bits.getAndSet(word, 0);
            while (taken != 0) {
                int bit = Long.numberOfTrailingZeros(taken);
                taken &= taken - 1;
                if (count < out.length) {
                    out[count] = (short) (word << 6 | bit);
                }
                count++;
            }
        }
        return count;
    }
}
