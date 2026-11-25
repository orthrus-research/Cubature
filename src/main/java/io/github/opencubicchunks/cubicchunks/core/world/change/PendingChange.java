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

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.util.ResourceLocation;

import java.util.Arrays;

/**
 * One cube's writes since the last drain: the sub-blocks touched and their causes. Marked on the server thread only,
 * so plain fields; the journal's queue publishes it to the drain, which runs on the same thread.
 */
public final class PendingChange {

    /** Receives a drained change: the cube's new version and the causes of the writes. */
    @FunctionalInterface
    public interface Committer {
        void commit(long version, ResourceLocation[] causes);
    }

    private static final ResourceLocation[] NO_CAUSES = new ResourceLocation[0];

    final CubePos pos;
    final Committer committer;
    long mask;
    private ResourceLocation[] causes = NO_CAUSES;
    private int causeCount;

    public PendingChange(CubePos pos, Committer committer) {
        this.pos = pos;
        this.committer = committer;
    }

    /**
     * Records a write. The common case, a cube written again with the cause it was last written with, is an OR, a
     * compare and a branch.
     *
     * @param bits  sub-block bits written, not 0
     * @return true when this is the first write since the last drain, so the cube must be queued
     */
    boolean mark(long bits, ResourceLocation cause) {
        boolean first = mask == 0;
        mask |= bits;
        if (causeCount == 0 || causes[causeCount - 1] != cause) {
            addCause(cause);
        }
        return first;
    }

    private void addCause(ResourceLocation cause) {
        for (int i = 0; i < causeCount; i++) {
            if (causes[i].equals(cause)) {
                return;
            }
        }
        if (causeCount == causes.length) {
            causes = Arrays.copyOf(causes, Math.max(2, causeCount * 2));
        }
        causes[causeCount++] = cause;
    }

    ResourceLocation[] takeCauses() {
        ResourceLocation[] taken = Arrays.copyOf(causes, causeCount);
        Arrays.fill(causes, 0, causeCount, null);
        causeCount = 0;
        mask = 0;
        return taken;
    }

    public boolean isEmpty() {
        return mask == 0;
    }
}
