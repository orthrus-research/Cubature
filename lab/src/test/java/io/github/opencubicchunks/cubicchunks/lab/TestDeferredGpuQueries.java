package io.github.opencubicchunks.cubicchunks.lab;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class TestDeferredGpuQueries {
    @Test public void neverReadsBeforeADeferredFrameAndPositiveAvailability() {
        Fake backend = new Fake(); DeferredGpuQueries queries = new DeferredGpuQueries(backend, 2); Samples samples = new Samples(8);
        int slot = queries.begin(10); queries.end(slot);
        queries.poll(11, samples); assertEquals(0, backend.polls); assertEquals(0, backend.reads);
        queries.poll(12, samples); assertEquals(1, backend.polls); assertEquals(0, backend.reads);
        backend.ready = true;
        queries.poll(13, samples); assertEquals(2, backend.reads);
        assertEquals(1L, samples.summary().get("count"));
        queries.close(); assertEquals(4, backend.deleted.size());
        assertEquals(0L, queries.summary().get("unresolvedAtClose"));
    }

    @Test public void aFullRingDropsMeasurementsAndNeverWaitsOrReusesAQuery() {
        Fake backend = new Fake(); DeferredGpuQueries queries = new DeferredGpuQueries(backend, 1); Samples samples = new Samples(8);
        int first = queries.begin(0); queries.end(first);
        assertEquals(-1, queries.begin(1)); assertEquals(2, backend.timestamps);
        queries.close();
        assertEquals(1L, queries.summary().get("droppedRingFull"));
        assertEquals(1L, queries.summary().get("unresolvedAtClose"));
        queries.poll(100, samples); queries.end(first);
        assertEquals(0, backend.reads); assertEquals(0, backend.polls); assertEquals(2, backend.timestamps);
        assertEquals(-1, queries.begin(101));
    }

    private static final class Fake implements DeferredGpuQueries.Backend {
        int ids, timestamps, polls, reads;
        boolean ready;
        final Set<Integer> deleted = new HashSet<>();
        public int create() { return ++ids; }
        public void timestamp(int query) { timestamps++; }
        public boolean available(int query) { polls++; return ready; }
        public long result(int query) { assertTrue(ready); reads++; return query * 1000L; }
        public void delete(int query) { assertTrue(deleted.add(query)); }
    }
}
