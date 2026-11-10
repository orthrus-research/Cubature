package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeClassification;
import io.github.opencubicchunks.cubicchunks.core.util.VisibleWatchersSortingList3D;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Column surface bands for visible-first delivery, read from the generator's optional
 * classification. A known band comes from immutable column data, so it is kept until its column
 * watcher goes. A column without a known band is asked again at most {@link #QUERIES_PER_TICK}
 * times per tick across all columns; until then it reads as unknown, which ranks its cubes as
 * surface cubes. The server thread owns this.
 */
final class SurfaceBands implements VisibleWatchersSortingList3D.Bands {
    static final int QUERIES_PER_TICK = 64;

    private final Supplier<Object> generator;
    private final Long2LongOpenHashMap known = new Long2LongOpenHashMap();
    private int queries;

    SurfaceBands(Supplier<Object> generator) {
        this.generator = generator;
        known.defaultReturnValue(VisibleWatchersSortingList3D.UNKNOWN_BAND);
    }

    void tick() { queries = 0; }

    void forget(int columnX, int columnZ) { known.remove(key(columnX, columnZ)); }

    @Override public long band(int columnX, int columnZ) {
        long key = key(columnX, columnZ);
        long band = known.get(key);
        if (band != VisibleWatchersSortingList3D.UNKNOWN_BAND || queries >= QUERIES_PER_TICK) return band;
        if (!(generator.get() instanceof ICubeClassification classification)) return band;
        queries++;
        @Nullable ICubeClassification.SurfaceBand found = classification.surfaceBand(columnX, columnZ);
        if (found == null) return band;
        band = VisibleWatchersSortingList3D.band(found.minimumCubeY(), found.maximumCubeY());
        known.put(key, band);
        return band;
    }

    int size() { return known.size(); }

    private static long key(int columnX, int columnZ) {
        return ((long) columnX << 32) | (columnZ & 0xffffffffL);
    }
}
