package io.github.opencubicchunks.cubicchunks.lab;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TestLabLodMerge {
    static final byte SURFACE = 5, LIGHT = 9, DOWN = LabLodMerge.DOWN_SAMPLED, EMPTY = LabLodMerge.EMPTY;
    static final int N = LabLodMerge.COLUMNS;

    /** Stand-ins with DH's member names: fastutil lists are read through elements() and size(). */
    static final class Steps { final byte[] a; Steps(byte[] a){this.a=a;} byte[] elements(){return a;} int size(){return a.length;} }
    /** A fastutil LongArrayList stand-in: the lab reads columns through toLongArray(). */
    static final class Longs extends ArrayList<Long> {
        static Longs of(long v) { Longs l = new Longs(); l.add(v); return l; }
        long[] toLongArray() { long[] a = new long[size()]; for (int i = 0; i < a.length; i++) a[i] = get(i); return a; }
    }

    static final class Source {
        final long pos; final Steps columnGenerationSteps; final Object[] dataPoints;
        Boolean applyToChildren, applyToParent;
        Source answer() { applyToChildren = true; applyToParent = true; return this; }
        Source(long pos, byte step, boolean data) {
            this.pos = pos; byte[] s = new byte[N]; Arrays.fill(s, step); columnGenerationSteps = new Steps(s);
            dataPoints = new Object[N];
            for (int i = 0; i < N; i++) dataPoints[i] = data ? Longs.of(1) : null;
        }
    }

    static byte[] fill(byte v) { byte[] a = new byte[N]; Arrays.fill(a, v); return a; }
    static boolean[] all(boolean v) { boolean[] a = new boolean[N]; Arrays.fill(a, v); return a; }

    @Test public void sameLevelRuleMatchesDh() {
        assertTrue(LabLodMerge.replaces(SURFACE, SURFACE));   // the overwrite under study
        assertTrue(LabLodMerge.replaces(DOWN, SURFACE));
        assertFalse(LabLodMerge.replaces(LIGHT, SURFACE));    // native-built columns survive
        assertTrue(LabLodMerge.replaces(EMPTY, DOWN));
        assertFalse(LabLodMerge.replaces(SURFACE, DOWN));
        assertFalse(LabLodMerge.replaces(EMPTY, EMPTY));
    }

    @Test public void coarseAnswerOverFineBuiltColumnsCountsAsLost() {
        BitSet fine = new BitSet(N); fine.set(0, N / 2);
        long[] r = LabLodMerge.sameLevel(fill(SURFACE), all(true), fill(SURFACE), all(true), fine, null);
        assertArrayEquals(new long[] {N / 2, N / 2, 0, SURFACE}, r);
        assertTrue("replaced columns are no longer fine-built", fine.isEmpty());
    }

    @Test public void holesAreFilledNotLostAndNullInputsSkipped() {
        boolean[] has = all(true); has[3] = false;
        byte[] target = fill(LIGHT); target[3] = EMPTY; target[4] = DOWN;
        boolean[] in = all(true); in[5] = false;
        long[] r = LabLodMerge.sameLevel(target, has, fill(SURFACE), in, null, null);
        assertArrayEquals(new long[] {0, 0, 2, SURFACE}, r);
    }

    @Test public void upwardMergeMarksTheChildQuadrant() {
        byte[] child = fill(SURFACE);
        child[LabLodMerge.index(0, 0)] = DOWN; // one copied-down column spoils its 2 x 2
        BitSet fine = LabLodMerge.fromBelow(null, 3, -2, child); // x odd, z even: offset (32, 0)
        assertFalse(fine.get(LabLodMerge.index(32, 0)));
        assertTrue(fine.get(LabLodMerge.index(33, 0)));
        assertTrue(fine.get(LabLodMerge.index(63, 31)));
        assertFalse(fine.get(LabLodMerge.index(0, 0)));
        assertFalse(fine.get(LabLodMerge.index(32, 32)));
        assertEquals(32 * 32 - 1, fine.cardinality());
    }

    @Test public void downwardStampCountsOnlyWhenAHoleIsFilled() {
        assertEquals(0, LabLodMerge.stamped(fill(SURFACE), all(true)));
        boolean[] has = all(true); has[7] = false;
        byte[] t = fill(SURFACE); t[8] = DOWN;
        assertEquals(N - 2, LabLodMerge.stamped(t, has));
    }

    @Test public void observeFollowsAParentThroughUpwardThenCoarseMerges() {
        long parent = LabLodTrace.encode(7, 10, 20), child = LabLodTrace.encode(6, 21, 40);
        Source target = new Source(parent, SURFACE, true);
        LabLodMerge.observe(target, parent, new Source(child, SURFACE, true));
        assertEquals(32 * 32, LabLodMerge.fine(parent).cardinality());
        // DH's own upward output (applyToChildren null) is not an answer and loses nothing.
        LabLodMerge.observe(target, parent, new Source(parent, SURFACE, true));
        assertEquals(32 * 32, LabLodMerge.fine(parent).cardinality());
        LabLodMerge.observe(target, parent, new Source(parent, SURFACE, true).answer());
        // DH merges: every column changes (the stand-in's new data), then the merge returns.
        for (int i = 0; i < N; i++) target.dataPoints[i] = Longs.of(2);
        LabLodMerge.afterSame(target, parent);
        assertTrue(LabLodMerge.fine(parent).isEmpty());
        assertEquals(32 * 32, LabLodMerge.lost(parent).cardinality());
        // The child's data comes up again: every lost column is restored.
        LabLodMerge.observe(target, parent, new Source(child, SURFACE, true));
        assertTrue(LabLodMerge.lost(parent).isEmpty());
    }

    @Test public void compareCountsOnlyColumnsThatChanged() {
        byte[] steps = fill(SURFACE);
        long[][] before = new long[N][], after = new long[N][];
        for (int i = 0; i < N; i++) { before[i] = new long[] {1}; after[i] = new long[] {1}; }
        BitSet fine = new BitSet(N); fine.set(0, 10);
        after[0] = new long[] {9};            // fine-built and changed: lost
        after[20] = new long[] {9};           // changed, not fine-built: replaced
        before[30] = null; after[30] = new long[] {3}; // was empty: filled
        byte[] afterSteps = steps.clone();
        after[1] = new long[] {9}; afterSteps[1] = LIGHT; // fine-built, replaced by native data: upgraded
        BitSet lost = new BitSet(N);
        assertArrayEquals(new long[] {1, 1, 1, 1}, LabLodMerge.compare(steps, before, afterSteps, after, fine, lost));
        assertTrue(lost.get(0));
        assertFalse(lost.get(1));
        assertEquals(8, fine.cardinality());
    }

    @Test public void answersAreRecognisedByDhPropagationFlags() {
        assertTrue(LabLodMerge.answer(true, true));
        assertTrue(LabLodMerge.answer(true, false));
        assertFalse(LabLodMerge.answer(null, true));   // DH propagating up
        assertFalse(LabLodMerge.answer(true, null));   // DH propagating down
    }

    @Test public void shownSplitsCopiedGeneratedAndEmptyColumns() {
        byte[] steps = fill(SURFACE); steps[0] = DOWN; steps[1] = DOWN; steps[2] = EMPTY;
        boolean[] has = all(true); has[2] = false; has[3] = false;
        assertArrayEquals(new long[] {2, N - 4, 2}, LabLodMerge.shown(steps, has));
    }

    @Test public void fallbackReportsTransitionsAndHiddenChildren() {
        LabLodFallback f = new LabLodFallback();
        f.begin(); f.fallback(1, 3, 4); f.fallback(2, 0, 1); f.end();
        assertArrayEquals(new long[] {2, 1, 3}, f.summary());
        assertTrue(f.active(1));
        f.begin(); f.fallback(2, 0, 1); f.end();
        assertArrayEquals(new long[] {1, 0, 0}, f.summary());
        assertFalse(f.active(1));
    }
}
