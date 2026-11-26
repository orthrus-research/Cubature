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

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Cubes whose settling window will end, in the order it ends. The window has one length for every cube, so a cube
 * added later ends later and a plain queue stays in order. A cube may be added more than once (when populated, then
 * when fully populated); whoever takes it checks that its window really has ended.
 *
 * @param <C> the cube
 */
public final class SettlingQueue<C> {

    private static final class Entry<C> {
        final C cube;
        final long deadline;

        Entry(C cube, long deadline) {
            this.cube = cube;
            this.deadline = deadline;
        }
    }

    private final ArrayDeque<Entry<C>> queue = new ArrayDeque<>();

    /** Server thread. */
    public void add(C cube, long deadline) {
        queue.add(new Entry<>(cube, deadline));
    }

    /** Hands every cube whose deadline is at or before {@code now} to {@code expired}, in order. Server thread. */
    public void expire(long now, Consumer<C> expired) {
        Entry<C> head;
        while ((head = queue.peek()) != null && head.deadline <= now) {
            queue.poll();
            expired.accept(head.cube);
        }
    }

    public int size() {
        return queue.size();
    }
}
