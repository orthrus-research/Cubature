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

import java.util.Arrays;

import javax.annotation.Nullable;

/**
 * What the cube source knows of whether the client's view is settled (docs/CUBE-SOURCE.md, section
 * 6): how many client ticks in a row nothing arrived, left or changed and no light work was left
 * queued, and whether the server has said it sent every cube of the box the client holds now.
 * Client thread.
 */
final class SettledState {

    private int quietTicks;
    private boolean busy;
    @Nullable private int[] box;
    private boolean boxSent;
    private boolean sentChanged;

    /** Something arrived, left or changed this tick. */
    void busy() {
        busy = true;
    }

    /** The end of a client tick; {@code lightQueued} whether the client still has light work queued. */
    void tick(boolean lightQueued) {
        if (busy || lightQueued) {
            quietTicks = 0;
        } else if (quietTicks < Integer.MAX_VALUE) {
            quietTicks++;
        }
        busy = false;
    }

    /** The server stated a new box: nothing of it is known to be sent yet, and the view is not quiet. */
    void box(int[] stated) {
        if (Arrays.equals(stated, box)) {
            return;
        }
        box = stated.clone();
        if (boxSent) {
            sentChanged = true;
        }
        boxSent = false;
        busy = true;
    }

    /** The server said it sent every cube of a box. It counts only for the box the client holds now. */
    void boxSent(int[] sent) {
        if (!boxSent && Arrays.equals(sent, box)) {
            boxSent = true;
            sentChanged = true;
        }
    }

    int quietTicks() {
        return quietTicks;
    }

    boolean boxSent() {
        return boxSent;
    }

    /** Whether {@link #boxSent} changed since the last call. */
    boolean takeSentChanged() {
        boolean was = sentChanged;
        sentChanged = false;
        return was;
    }

    /** A new world: nothing known. */
    void clear() {
        quietTicks = 0;
        busy = false;
        box = null;
        boxSent = false;
        sentChanged = false;
    }
}
