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

/**
 * The server's single stream of block changes in cubic worlds. Every block change that is not generation feeds it:
 * {@code setBlockState} from any mod, and {@link io.github.opencubicchunks.cubicchunks.api.world.ICube#markEdited}
 * for writers that change cube storage directly. Changes are coalesced to one per cube per tick and dispatched at the
 * end of the tick. Get it from {@link ChangeApi#bus()}.
 */
public interface IWorldChangeBus {

    /**
     * Starts delivering changes that pass {@code filter} to {@code listener}, from the next dispatch on. Safe to call
     * from any thread, including from inside a listener.
     *
     * @return a handle whose {@link Subscription#close()} stops delivery
     */
    Subscription subscribe(ChangeFilter filter, IWorldChangeListener listener);

    /** A subscription; close it to stop delivery. Closing twice does nothing. */
    interface Subscription extends AutoCloseable {
        @Override void close();
    }
}
