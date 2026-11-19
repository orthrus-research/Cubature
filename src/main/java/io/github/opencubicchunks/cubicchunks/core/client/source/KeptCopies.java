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
package io.github.opencubicchunks.cubicchunks.core.client.source;

import java.util.ArrayList;

/**
 * The holders of kept cube copies, and when each copy was last handed to a snapshot. A copy is kept
 * on its cube so that the snapshots of a neighborhood share it while the cube's version stands; once
 * no snapshot has asked for it for a while, it is let go, and the next snapshot that needs it copies
 * the cube again. A snapshot already taken keeps its own reference, so letting go changes nothing in
 * flight.
 *
 * <p>Each tick looks at a bounded part of the holders, so a holder that has let go of its copy, or
 * whose cube left, is forgotten within a few ticks. Client thread.
 *
 * @param <T> a holder of a copy: a cube
 */
final class KeptCopies<T> {

    /** How a holder is read and told to let go. */
    interface Holders<T> {
        /** The tick its copy was last handed to a snapshot, or a negative number when it holds no copy. */
        long lastAsked(T holder);

        /** Lets go of its copy. */
        void drop(T holder);
    }

    /** How many holders a tick looks at. */
    static final int PER_TICK = 2048;

    private final Holders<T> holders;
    private final ArrayList<T> held = new ArrayList<>();
    private int cursor;
    private long dropped;
    private long worldDropped;

    KeptCopies(Holders<T> holders) {
        this.holders = holders;
    }

    /** Notes a holder that has just been given a copy where it held none. */
    void kept(T holder) {
        held.add(holder);
    }

    /**
     * Looks at the next part of the holders: forgets those that hold no copy and lets go of the copies
     * no snapshot has asked for in {@code idleTicks} ticks. With {@code idleTicks} 0 or less every copy
     * is kept until its cube changes or leaves, as before this existed, and only the holders that let
     * go of theirs otherwise are forgotten.
     */
    void tick(long tick, int idleTicks) {
        for (int looked = 0; looked < PER_TICK && !held.isEmpty(); looked++) {
            if (cursor >= held.size()) {
                cursor = 0;
            }
            T holder = held.get(cursor);
            long asked = holders.lastAsked(holder);
            boolean idle = idleTicks > 0 && asked >= 0 && tick - asked >= idleTicks;
            if (idle) {
                holders.drop(holder);
                dropped++;
                worldDropped++;
            }
            if (asked < 0 || idle) {
                // The last holder takes its place, and is looked at next.
                int last = held.size() - 1;
                held.set(cursor, held.get(last));
                held.remove(last);
            } else {
                cursor++;
            }
        }
    }

    /** Forgets every holder, as a world goes; their copies go with their cubes. */
    void clear() {
        held.clear();
        cursor = 0;
        worldDropped = 0;
    }

    /** How many holders are known; at least those that hold a copy. */
    int size() {
        return held.size();
    }

    /** Copies let go since the client started. */
    long dropped() {
        return dropped;
    }

    /** Copies let go in this world. */
    long worldDropped() {
        return worldDropped;
    }
}
