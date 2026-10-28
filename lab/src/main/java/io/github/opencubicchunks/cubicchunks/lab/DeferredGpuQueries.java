package io.github.opencubicchunks.cubicchunks.lab;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded query ownership. No wait operation is exposed; results require a later frame and positive availability. */
final class DeferredGpuQueries {
    interface Backend {
        int create();
        void timestamp(int query);
        boolean available(int query);
        long result(int query);
        void delete(int query);
    }

    private final Backend backend;
    private final int[] starts, ends;
    private final long[] frames;
    private final boolean[] pending, ended;
    private int next;
    private long issued, resolved, dropped, invalid, unresolved;
    private boolean closed;

    DeferredGpuQueries(Backend backend, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Query capacity must be positive");
        this.backend = backend;
        starts = new int[capacity]; ends = new int[capacity]; frames = new long[capacity];
        pending = new boolean[capacity]; ended = new boolean[capacity];
        try {
            for (int i = 0; i < capacity; i++) {
                starts[i] = backend.create(); ends[i] = backend.create();
                if (starts[i] == 0 || ends[i] == 0) throw new IllegalStateException("GPU query allocation returned zero");
            }
        } catch (RuntimeException failure) {
            for (int query : starts) if (query != 0) backend.delete(query);
            for (int query : ends) if (query != 0) backend.delete(query);
            throw failure;
        }
    }

    int begin(long frame) {
        if (closed) return -1;
        if (pending[next]) { dropped++; return -1; }
        int slot = next; next = (next + 1) % pending.length;
        pending[slot] = true; ended[slot] = false; frames[slot] = frame; issued++;
        backend.timestamp(starts[slot]);
        return slot;
    }

    void end(int slot) {
        if (closed || slot < 0) return;
        backend.timestamp(ends[slot]); ended[slot] = true;
    }

    void poll(long frame, Samples samples) {
        if (closed) return;
        for (int i = 0; i < pending.length; i++) {
            if (!pending[i] || !ended[i] || frame - frames[i] < 2 || !backend.available(ends[i])) continue;
            long duration = backend.result(ends[i]) - backend.result(starts[i]);
            if (duration < 0) invalid++;
            else samples.add(duration);
            pending[i] = false; resolved++;
        }
    }

    void close() {
        if (closed) return;
        for (int i = 0; i < pending.length; i++) {
            if (pending[i]) unresolved++;
            backend.delete(starts[i]); backend.delete(ends[i]);
        }
        closed = true;
    }

    Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capacity", starts.length); result.put("issued", issued); result.put("resolved", resolved);
        result.put("droppedRingFull", dropped); result.put("invalidDurations", invalid);
        result.put("unresolvedAtClose", unresolved); result.put("minimumDeferredFrames", 2);
        result.put("scope", "Timestamp command interval around VBO upload; not exclusive DMA or presentation time. Unresolved queries are discarded without waiting.");
        return result;
    }
}
