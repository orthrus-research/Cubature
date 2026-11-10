package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeClassification;
import io.github.opencubicchunks.cubicchunks.core.util.VisibleWatchersSortingList3D;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class TestSurfaceBands {
    private static final class Classifier implements ICubeClassification {
        final Map<Long, SurfaceBand> bands = new HashMap<>();
        int queries;
        @Override public CubeClass cubeClass(int x, int y, int z) { return CubeClass.UNKNOWN; }
        @Override public SurfaceBand surfaceBand(int x, int z) { queries++; return bands.get(((long) x << 32) | (z & 0xffffffffL)); }
        void put(int x, int z, int min, int max) { bands.put(((long) x << 32) | (z & 0xffffffffL), new SurfaceBand(min, max)); }
    }

    @Test public void knownBandsAreCachedAndForgotten() {
        Classifier generator = new Classifier();
        generator.put(1, -2, 3, 5);
        SurfaceBands bands = new SurfaceBands(() -> generator);
        assertEquals(VisibleWatchersSortingList3D.band(3, 5), bands.band(1, -2));
        assertEquals(VisibleWatchersSortingList3D.band(3, 5), bands.band(1, -2));
        assertEquals(1, generator.queries);
        bands.forget(1, -2);
        assertEquals(0, bands.size());
        assertEquals(VisibleWatchersSortingList3D.band(3, 5), bands.band(1, -2));
        assertEquals(2, generator.queries);
    }

    @Test public void unknownColumnsAreAskedWithinTheTickBudget() {
        Classifier generator = new Classifier();
        SurfaceBands bands = new SurfaceBands(() -> generator);
        for (int i = 0; i < SurfaceBands.QUERIES_PER_TICK + 10; i++)
            assertEquals(VisibleWatchersSortingList3D.UNKNOWN_BAND, bands.band(i, 0));
        assertEquals(SurfaceBands.QUERIES_PER_TICK, generator.queries);
        generator.put(0, 0, -1, 2);
        assertEquals(VisibleWatchersSortingList3D.UNKNOWN_BAND, bands.band(0, 0));
        bands.tick();
        assertEquals(VisibleWatchersSortingList3D.band(-1, 2), bands.band(0, 0));
    }

    @Test public void generatorsWithoutClassificationGiveUnknownBands() {
        SurfaceBands none = new SurfaceBands(() -> null), plain = new SurfaceBands(Object::new);
        assertEquals(VisibleWatchersSortingList3D.UNKNOWN_BAND, none.band(0, 0));
        assertEquals(VisibleWatchersSortingList3D.UNKNOWN_BAND, plain.band(0, 0));
    }

    @Test public void negativeBandsRoundTrip() {
        long packed = VisibleWatchersSortingList3D.band(-7, -3);
        assertEquals(-7, (int) (packed >> 32));
        assertEquals(-3, (int) packed);
        assertNotEquals(VisibleWatchersSortingList3D.UNKNOWN_BAND, packed);
    }
}
