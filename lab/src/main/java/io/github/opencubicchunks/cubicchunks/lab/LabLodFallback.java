package io.github.opencubicchunks.cubicchunks.lab;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks, for the LOD trace, DH's quad-tree fallback: where finer sections are wanted but not all four children of a
 * section can render, DH draws that section in their place and disables all four (LodQuadTree.onDetailLevelTooLow). Children that could render are then hidden behind coarser data. One tree pass
 * runs on DH's tree thread between {@link #begin} and {@link #end}; a parent that starts or stops falling back emits
 * fallback-start (a renderable children hidden, b children present) or fallback-end, so the trace stays bounded.
 */
public final class LabLodFallback {
    private Map<Long, Integer> previous = new HashMap<>(), current = new HashMap<>();
    private long hiddenNow;

    public void begin() { Map<Long, Integer> t = previous; previous = current; current = t; current.clear(); hiddenNow = 0; }

    /** A parent drawn in place of its children; {@code renderable} children could have drawn, of {@code present}. */
    public void fallback(long pos, int renderable, int present) {
        if (current.put(pos, renderable) == null) hiddenNow += renderable;
        if (!previous.containsKey(pos)) LabLodTrace.event("fallback-start", pos, renderable, present, 0, 0);
    }

    public void end() {
        for (Long pos : previous.keySet()) if (!current.containsKey(pos)) LabLodTrace.event("fallback-end", pos);
    }

    /** Parents falling back in the last pass, those hiding at least one renderable child, and those children. */
    public long[] summary() {
        long hiding = 0;
        for (int r : current.values()) if (r > 0) hiding++;
        return new long[] {current.size(), hiding, hiddenNow};
    }

    /** For tests: whether a parent fell back in the last finished pass. */
    boolean active(long pos) { return current.containsKey(pos); }
}
