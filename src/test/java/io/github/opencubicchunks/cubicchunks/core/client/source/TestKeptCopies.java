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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TestKeptCopies {

    /** A holder with a copy asked for at a tick, or none. */
    static final class Holder {
        long asked;

        Holder(long asked) {
            this.asked = asked;
        }
    }

    private static final KeptCopies.Holders<Holder> HOLDERS = new KeptCopies.Holders<Holder>() {
        @Override
        public long lastAsked(Holder holder) {
            return holder.asked;
        }

        @Override
        public void drop(Holder holder) {
            holder.asked = -1;
        }
    };

    @Test
    public void aCopyAskedForRecentlyIsKept() {
        KeptCopies<Holder> kept = new KeptCopies<>(HOLDERS);
        Holder cube = new Holder(100);
        kept.kept(cube);
        kept.tick(299, 200);
        assertEquals(100, cube.asked);
        assertEquals(1, kept.size());
        assertEquals(0, kept.dropped());
    }

    @Test
    public void aCopyNoSnapshotAskedForIsLetGoAndItsHolderForgotten() {
        KeptCopies<Holder> kept = new KeptCopies<>(HOLDERS);
        Holder idle = new Holder(100);
        Holder busy = new Holder(290);
        kept.kept(idle);
        kept.kept(busy);
        kept.tick(300, 200);
        assertEquals(-1, idle.asked);
        assertEquals(290, busy.asked);
        assertEquals(1, kept.size());
        assertEquals(1, kept.dropped());
        assertEquals(1, kept.worldDropped());
    }

    @Test
    public void aHolderThatLetGoOtherwiseIsForgottenWithoutCounting() {
        KeptCopies<Holder> kept = new KeptCopies<>(HOLDERS);
        Holder gone = new Holder(-1);
        kept.kept(gone);
        kept.tick(1000, 200);
        assertEquals(0, kept.size());
        assertEquals(0, kept.dropped());
    }

    @Test
    public void withNoIdleTimeEveryCopyIsKept() {
        KeptCopies<Holder> kept = new KeptCopies<>(HOLDERS);
        Holder old = new Holder(1);
        kept.kept(old);
        kept.kept(new Holder(-1));
        kept.tick(1_000_000, 0);
        assertEquals(1, old.asked);
        assertEquals(1, kept.size());
    }

    @Test
    public void aTickLooksAtABoundedPartAndEveryHolderIsReached() {
        KeptCopies<Holder> kept = new KeptCopies<>(HOLDERS);
        int count = KeptCopies.PER_TICK * 3 + 7;
        Holder[] all = new Holder[count];
        for (int i = 0; i < count; i++) {
            all[i] = new Holder(0);
            kept.kept(all[i]);
        }
        kept.tick(500, 200);
        assertEquals(KeptCopies.PER_TICK, kept.dropped());
        for (int t = 0; t < 4; t++) {
            kept.tick(501 + t, 200);
        }
        assertEquals(count, kept.dropped());
        assertEquals(0, kept.size());
        for (Holder holder : all) {
            assertEquals(-1, holder.asked);
        }
    }

    @Test
    public void clearingForgetsTheWorldsHolders() {
        KeptCopies<Holder> kept = new KeptCopies<>(HOLDERS);
        kept.kept(new Holder(0));
        kept.tick(500, 200);
        kept.kept(new Holder(0));
        kept.clear();
        assertEquals(0, kept.size());
        assertEquals(0, kept.worldDropped());
        assertEquals(1, kept.dropped());
    }
}
