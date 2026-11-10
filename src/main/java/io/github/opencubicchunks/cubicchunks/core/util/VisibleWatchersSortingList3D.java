package io.github.opencubicchunks.cubicchunks.core.util;

import io.github.opencubicchunks.cubicchunks.api.util.XYZAddressable;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Orders cube watchers by visibility class, then by distance to the nearest player. Classes, best
 * over all players:
 * <ol>
 * <li>near: within two cubes of a player on every axis (a 5 x 5 x 5 neighbourhood);</li>
 * <li>surface: inside its column's surface band, or the band is unknown;</li>
 * <li>between: from a player's cube Y toward the surface band, inclusive at the player and
 * exclusive of the band, so a player underground sees its own level and the way up first;</li>
 * <li>hidden: everything else.</li>
 * </ol>
 * With no known band every cube beyond the near field is a surface cube, which is plain distance
 * order as before. A hidden cube that has waited {@link #CATCH_UP_AGE} ticks may be promoted to a
 * catch-up bucket between the near and surface classes, at most one per {@link #CATCH_UP_PERIOD}
 * ticks, so a long stream of visible work cannot starve it.
 */
public final class VisibleWatchersSortingList3D<T extends BucketSorterEntry & XYZAddressable> extends WatchersSortingList3D<T> {
    /** Packed inclusive cube-Y band of a column, or {@link #UNKNOWN_BAND}. */
    @FunctionalInterface
    public interface Bands {
        long band(int columnX, int columnZ);
    }

    public static final long UNKNOWN_BAND = Long.MIN_VALUE;
    static final int NEAR_RADIUS = 2;
    static final long CATCH_UP_AGE = 200, CATCH_UP_PERIOD = 20;
    /** Hidden entries examined per catch-up selection, continuing where the last one stopped. */
    static final int CATCH_UP_SCAN = 256;

    private static final int DISTANCES = BUCKET_COUNT;
    static final int CATCH_UP = DISTANCES;
    private static final int[] OFFSET = {0, DISTANCES + 1, 2 * DISTANCES + 1, 3 * DISTANCES + 1};

    public static long band(int minimumCubeY, int maximumCubeY) {
        return ((long) minimumCubeY << 32) | (maximumCubeY & 0xffffffffL);
    }

    private final Bands bands;
    private final Reference2LongOpenHashMap<T> since = new Reference2LongOpenHashMap<>();
    private final ReferenceOpenHashSet<T> promoted = new ReferenceOpenHashSet<>();
    private long ticks, lastCatchUp = -CATCH_UP_PERIOD;
    private int scanBucket = OFFSET[3], scanIndex;

    public VisibleWatchersSortingList3D(int intrusiveCollectionId, Supplier<Collection<EntityPlayer>> players, Bands bands) {
        super(intrusiveCollectionId, players, 4 * DISTANCES + 1);
        this.bands = bands;
    }

    @Override public void tick() {
        super.tick();
        age();
    }

    /** One tick of waiting: ages every entry and promotes at most one hidden entry per period. */
    void age() {
        ticks++;
        if (ticks - lastCatchUp >= CATCH_UP_PERIOD && catchUp()) lastCatchUp = ticks;
    }

    @Override public void add(T element) {
        if (contains(element)) return;
        since.putIfAbsent(element, ticks);
        super.add(element);
    }

    @Override public void remove(T element) {
        if (!contains(element)) return;
        super.remove(element);
        since.removeLong(element);
        promoted.remove(element);
    }

    @Override int computeBucketIdx(T element) {
        if (promoted.contains(element)) return CATCH_UP;
        return OFFSET[visibility(element)] + distanceBucket(element);
    }

    /** The element's class, 0 (near) to 3 (hidden), best over all players; 1 with no players. */
    int visibility(T element) {
        long[] players = playerPositions;
        if (players.length == 0) return 1;
        int x = element.getX(), y = element.getY(), z = element.getZ();
        int best = 3;
        long packed = 0;
        boolean banded = false;
        for (int i = 0; i < players.length; i += 3) {
            long dx = x - players[i], dy = y - players[i + 1], dz = z - players[i + 2];
            if (Math.abs(dx) <= NEAR_RADIUS && Math.abs(dy) <= NEAR_RADIUS && Math.abs(dz) <= NEAR_RADIUS) return 0;
            if (best == 1) continue;
            if (!banded) {
                packed = bands.band(x, z);
                banded = true;
                if (packed == UNKNOWN_BAND) {
                    best = 1;
                    continue;
                }
            }
            int minimum = (int) (packed >> 32), maximum = (int) packed;
            if (y >= minimum && y <= maximum) {
                best = 1;
                continue;
            }
            long py = players[i + 1];
            if (py <= y && y < minimum || maximum < y && y <= py) best = 2;
        }
        return best;
    }

    /** Promotes the first hidden entry found that has waited long enough. */
    private boolean catchUp() {
        int scanned = 0;
        int first = OFFSET[3], end = OFFSET[3] + DISTANCES;
        for (int visitedBuckets = 0; visitedBuckets <= DISTANCES && scanned < CATCH_UP_SCAN; ) {
            ObjectArrayList<T> list = buckets[scanBucket];
            while (scanIndex < list.size() && scanned < CATCH_UP_SCAN) {
                T element = list.get(scanIndex++);
                scanned++;
                if (ticks - since.getLong(element) >= CATCH_UP_AGE && visibility(element) == 3) {
                    promoted.add(element);
                    super.remove(element);
                    super.add(element);
                    return true;
                }
            }
            if (scanIndex >= list.size()) {
                scanIndex = 0;
                if (++scanBucket >= end) scanBucket = first;
                visitedBuckets++;
            }
        }
        return false;
    }

    /** Entries promoted to the catch-up bucket and still queued; for tests and diagnostics. */
    int promotedCount() { return promoted.size(); }
}
