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

import net.minecraft.util.ResourceLocation;

import java.util.Arrays;

import javax.annotation.Nullable;

/**
 * The cause of the block writes the current thread makes, as a stack. Push a cause before a run of writes and pop it
 * after, in a {@code try}/{@code finally}:
 *
 * <pre>{@code
 * ChangeScope.push(new ResourceLocation("mymod", "paste"));
 * try {
 *     // world.setBlockState(...) for every block of the paste
 * } finally {
 *     ChangeScope.pop();
 * }
 * }</pre>
 *
 * Every write the bus journals carries the innermost cause, or {@link ChangeCauses#UNKNOWN} when none is pushed.
 * Writes under {@link ChangeCauses#GENERATION} are not journaled. Scopes belong to the thread that pushed them; block
 * writes are made on the server thread, so that is where scopes matter.
 */
public final class ChangeScope {

    private static final ThreadLocal<Stack> STACK = ThreadLocal.withInitial(Stack::new);

    private ChangeScope() {
    }

    /** Makes {@code cause} the cause of this thread's writes until the matching {@link #pop()}. */
    public static void push(ResourceLocation cause) {
        if (cause == null) {
            throw new NullPointerException("cause");
        }
        STACK.get().push(cause);
    }

    /**
     * Ends the innermost scope this thread pushed.
     *
     * @throws IllegalStateException when this thread has no scope open
     */
    public static void pop() {
        STACK.get().pop();
    }

    /** The cause of this thread's writes now: the innermost pushed, or {@link ChangeCauses#UNKNOWN}. */
    public static ResourceLocation current() {
        ResourceLocation top = STACK.get().peek();
        return top == null ? ChangeCauses.UNKNOWN : top;
    }

    /** How many scopes this thread has open, for checks that every push was popped. */
    public static int depth() {
        return STACK.get().size;
    }

    private static final class Stack {
        private ResourceLocation[] causes = new ResourceLocation[4];
        private int size;

        void push(ResourceLocation cause) {
            if (size == causes.length) {
                causes = Arrays.copyOf(causes, size * 2);
            }
            causes[size++] = cause;
        }

        void pop() {
            if (size == 0) {
                throw new IllegalStateException("ChangeScope.pop() without a matching push()");
            }
            causes[--size] = null;
        }

        @Nullable ResourceLocation peek() {
            return size == 0 ? null : causes[size - 1];
        }
    }
}
