package io.github.opencubicchunks.cubicchunks.lab;

import java.util.BitSet;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts, for the LOD trace, what DH's full-data merge does to columns that already hold data. DH replaces a column of
 * a section with a same-detail input whose generation step is the same or more complete, so a coarse preview answer
 * (one sample per cell, SURFACE) can replace a column that DH built by downsampling finer data (also SURFACE). This
 * class remembers which columns of each section last came from the level below with generated data ("fine-built"),
 * and reports, before DH merges, how many of those an answer at the same detail is about to replace (lost), and how
 * many lost columns a later merge from below builds again (restored). An answer is a network response or a generated
 * tile: DH sets both of its propagation flags (applyToChildren true, applyToParent set); its own propagation leaves
 * applyToChildren null going up and applyToParent null going down, and those merges are not counted. It also reports
 * a downward merge that stamps DOWN_SAMPLED over columns that keep their own data. It reads only, as DH's own rule
 * reads, and changes nothing.
 */
public final class LabLodMerge {
    static final int WIDTH = 64, COLUMNS = WIDTH * WIDTH;
    static final byte DOWN_SAMPLED = -1, EMPTY = 0;
    /** Sections tracked at most; past it the map is cleared, and the json summary counts the clears. */
    static final int MAX_TRACKED = 200_000;
    private static final ConcurrentHashMap<Long, BitSet> FINE = new ConcurrentHashMap<>(), LOST = new ConcurrentHashMap<>();

    private LabLodMerge() {}

    /** Whether DH's same-level merge replaces this column (FullDataSourceV2.updateFromSameDetailLevel). */
    static boolean replaces(byte target, byte input) {
        if (input == DOWN_SAMPLED) return target == EMPTY || target == DOWN_SAMPLED;
        return input != EMPTY && target <= input;
    }

    /**
     * A same-detail merge: {a fine-built columns replaced, other generated columns replaced, empty or downsampled
     * columns filled, the most complete input step among replaced columns}. {@code inputHas} is false where the input
     * column is null, which DH skips. A replaced column is no longer fine-built, so its bit is cleared; a lost one is
     * marked in {@code lost} when given.
     */
    static long[] sameLevel(byte[] target, boolean[] targetHas, byte[] input, boolean[] inputHas, BitSet fine, BitSet lost) {
        long lostCount = 0, replaced = 0, filled = 0; int top = Byte.MIN_VALUE;
        for (int i = 0; i < COLUMNS; i++) {
            if (!inputHas[i] || !replaces(target[i], input[i])) continue;
            if (!targetHas[i] || target[i] == EMPTY || target[i] == DOWN_SAMPLED) filled++;
            else if (fine != null && fine.get(i)) { lostCount++; if (lost != null) lost.set(i); }
            else replaced++;
            if (fine != null) fine.clear(i);
            top = Math.max(top, input[i]);
        }
        return new long[] {lostCount, replaced, filled, top == Byte.MIN_VALUE ? 0 : top};
    }

    /** Index of a column, as FullDataSourceV2.relativePosToIndex. */
    static int index(int x, int z) { return x * WIDTH + z; }

    /**
     * An upward merge writes the child's quadrant: a parent column is fine-built when the minimum step of its 2 x 2
     * child columns is a generated step (DH's own minimum). Returns the updated set.
     */
    static BitSet fromBelow(BitSet fine, int childX, int childZ, byte[] child) {
        BitSet out = fine == null ? new BitSet(COLUMNS) : fine;
        int ox = (childX & 1) * WIDTH / 2, oz = (childZ & 1) * WIDTH / 2;
        for (int x = 0; x < WIDTH; x += 2)
            for (int z = 0; z < WIDTH; z += 2) {
                byte min = (byte) Math.min(Math.min(child[index(x, z)], child[index(x + 1, z)]),
                        Math.min(child[index(x, z + 1)], child[index(x + 1, z + 1)]));
                out.set(index(x / 2 + ox, z / 2 + oz), min > EMPTY);
            }
        return out;
    }

    /**
     * A downward merge (DH downsampleFromOneAboveDetailLevel) fills only empty columns but sets every column's step to
     * DOWN_SAMPLED; DH saves that only when it filled one. Returns the generated columns so stamped, 0 when none is.
     */
    static long stamped(byte[] target, boolean[] targetHas) {
        long real = 0; boolean fills = false;
        for (int i = 0; i < COLUMNS; i++) {
            if (!targetHas[i]) fills = true;
            else if (target[i] != EMPTY && target[i] != DOWN_SAMPLED) real++;
        }
        return fills ? real : 0;
    }

    /** For tests: the fine-built and the lost columns remembered for a section. */
    static BitSet fine(long pos) { return FINE.get(pos); }
    static BitSet lost(long pos) { return LOST.get(pos); }

    /** Lost columns that are fine-built again; they are no longer lost. */
    static long restored(BitSet fine, BitSet lost) {
        if (fine == null || lost == null) return 0;
        BitSet again = (BitSet) lost.clone();
        again.and(fine);
        lost.andNot(again);
        return again.cardinality();
    }

    /** An answer, by DH's propagation flags (FullDataSourceV2 applyToChildren, applyToParent). */
    static boolean answer(Object applyToChildren, Object applyToParent) {
        return Boolean.TRUE.equals(applyToChildren) && applyToParent != null;
    }

    private static long clears;
    public static long clears() { return clears; }

    /** Called before DH merges {@code input} into {@code target}; both are FullDataSourceV2. */
    public static void observe(Object target, long targetPos, Object input) {
        long inputPos = LabLodTrace.longField(input, "pos");
        int td = LabLodTrace.detail(targetPos), id = LabLodTrace.detail(inputPos);
        byte[] steps = steps(target);
        if (steps == null) return;
        boolean[] has = has(target);
        if (id == td) {
            byte[] in = steps(input);
            if (in == null) return;
            if (!answer(LabLodTrace.field(input, "applyToChildren"), LabLodTrace.field(input, "applyToParent"))) return;
            if (LOST.size() >= MAX_TRACKED) { LOST.clear(); clears++; }
            long[] r = sameLevel(steps, has, in, nonNull(input), FINE.get(targetPos),
                    LOST.computeIfAbsent(targetPos, k -> new BitSet(COLUMNS)));
            if (r[0] + r[1] + r[2] > 0) LabLodTrace.event("merge-same", targetPos, r[0], r[1], r[2], r[3]);
        } else if (id + 1 == td) {
            byte[] child = steps(input);
            if (child == null) return;
            if (FINE.size() >= MAX_TRACKED) { FINE.clear(); clears++; }
            BitSet fine = FINE.compute(targetPos, (k, v) -> fromBelow(v, LabLodTrace.x(inputPos), LabLodTrace.z(inputPos), child));
            long again = restored(fine, LOST.get(targetPos));
            if (again > 0) LabLodTrace.event("merge-restore", targetPos, again);
        } else if (id - 1 == td) {
            long real = stamped(steps, has);
            if (real > 0) LabLodTrace.event("merge-stamp", targetPos, real);
        }
    }

    /**
     * {copied-down columns with data, generated columns with data, columns without data}: what a render build of a
     * section reads, so a trace shows when a section DH draws holds a coarser level's data copied down to it.
     */
    static long[] shown(byte[] steps, boolean[] has) {
        long copied = 0, generated = 0, empty = 0;
        for (int i = 0; i < COLUMNS; i++) {
            if (!has[i]) empty++;
            else if (steps[i] == DOWN_SAMPLED || steps[i] == EMPTY) copied++;
            else generated++;
        }
        return new long[] {copied, generated, empty};
    }

    /** A render build reads a section's full data: render-data with {@link #shown}. */
    public static void rendered(Object source) {
        byte[] steps = steps(source);
        if (steps == null) return;
        long[] r = shown(steps, has(source));
        LabLodTrace.event("render-data", LabLodTrace.longField(source, "pos"), r[0], r[1], r[2], 0);
    }

    /** The section's column steps (a fastutil ByteArrayList, read through its backing array). */
    private static byte[] steps(Object source) {
        Object list = LabLodTrace.field(source, "columnGenerationSteps");
        if (list == null) return null;
        byte[] raw = (byte[]) LabLodTrace.call(list, "elements");
        Object size = LabLodTrace.call(list, "size");
        return size instanceof Number && ((Number) size).intValue() >= COLUMNS ? raw : null;
    }

    /** Columns whose data list exists and is not empty. */
    private static boolean[] has(Object source) {
        Object[] columns = (Object[]) LabLodTrace.field(source, "dataPoints");
        boolean[] out = new boolean[COLUMNS];
        for (int i = 0; i < COLUMNS && i < columns.length; i++)
            out[i] = columns[i] instanceof Collection && !((Collection<?>) columns[i]).isEmpty();
        return out;
    }

    /** Columns whose data list exists; DH skips an input column only when it is null. */
    private static boolean[] nonNull(Object source) {
        Object[] columns = (Object[]) LabLodTrace.field(source, "dataPoints");
        boolean[] out = new boolean[COLUMNS];
        for (int i = 0; i < COLUMNS && i < columns.length; i++) out[i] = columns[i] != null;
        return out;
    }
}
