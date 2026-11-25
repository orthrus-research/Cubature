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
package io.github.opencubicchunks.cubicchunks.api.change;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.world.World;

import java.util.List;

import javax.annotation.Nullable;

/**
 * One server tick's changes in one world that passed a subscriber's filter. Handed to
 * {@link IWorldChangeListener#onChanges} on the server thread at the end of the tick; valid only during that call.
 */
public interface CubeChangeBatch {

    /** The world the changes were made in. */
    World world();

    /** The world's total time when the batch was made. */
    long tick();

    /** The changes, at most one for each cube. Unmodifiable. */
    List<CubeChange> changes();

    /**
     * A copy of a changed cube's blocks as they are now, taken on the server thread. The copy is safe to read from
     * any thread afterwards, so heavy work on it belongs on the subscriber's own pool, not in the listener.
     * Callable only during {@link IWorldChangeListener#onChanges}.
     *
     * @return the copy, or null when the cube is no longer loaded
     * @throws IllegalStateException when called outside the listener call
     */
    @Nullable ICubeBlockCopy snapshot(CubePos pos);
}
