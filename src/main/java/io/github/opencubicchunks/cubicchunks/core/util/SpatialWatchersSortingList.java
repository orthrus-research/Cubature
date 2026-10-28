/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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
package io.github.opencubicchunks.cubicchunks.core.util;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Shared intrusive bucket ownership for column and cube watcher queues. Server-thread only. */
abstract class SpatialWatchersSortingList<T extends BucketSorterEntry> implements Iterable<T> {
    protected final ObjectArrayList<T>[] buckets;
    protected final int intrusiveCollectionId;
    private final int dimensions;
    private final Supplier<Collection<EntityPlayer>> playersSupplier;
    protected long[] playerPositions = new long[0];
    private int distributingBucket;
    protected int size;

    @SuppressWarnings("unchecked")
    SpatialWatchersSortingList(int id, Supplier<Collection<EntityPlayer>> players, int dimensions) {
        this.intrusiveCollectionId = id;
        this.playersSupplier = players;
        this.dimensions = dimensions;
        buckets = new ObjectArrayList[(int) (CubicChunks.MAX_RENDER_DISTANCE * Math.sqrt(dimensions)) + 1];
        for (int i = 0; i < buckets.length; i++) buckets[i] = new ObjectArrayList<>();
        updatePlayerPositions();
    }

    public void tick() {
        updatePlayerPositions();
        for (int visited = 0, redistributed = 0; visited < buckets.length && redistributed < 10; visited++) {
            int bucket = distributingBucket;
            if (++distributingBucket == buckets.length) distributingBucket = 0;
            if (!buckets[bucket].isEmpty()) {
                redistributeBucket(bucket);
                redistributed++;
            }
        }
    }

    private void updatePlayerPositions() {
        Collection<EntityPlayer> players = playersSupplier.get();
        int length = players.size() * dimensions;
        if (playerPositions.length != length) playerPositions = new long[length];
        int i = 0;
        for (EntityPlayer player : players) {
            playerPositions[i++] = Coords.blockToCube(player.posX);
            if (dimensions == 3) playerPositions[i++] = Coords.blockToCube(player.posY);
            playerPositions[i++] = Coords.blockToCube(player.posZ);
        }
    }

    // Keep address-dependent hot loops in the concrete 2D/3D classes for direct geometry calls.
    public abstract void redistributeBucket(int bucket);
    public abstract void add(T element);

    public boolean isEmpty() { return size == 0; }
    public boolean contains(T element) { return element.getSorterStorage(intrusiveCollectionId) != 0; }
    @Override public Iterator<T> iterator() { return iteratorUpToDistance(buckets.length - 1); }

    public Iterator<T> iteratorUpToDistance(int maxDistance) {
        int limit = Math.min(maxDistance, buckets.length - 1);
        return new Iterator<T>() {
            private int bucket, index;
            private T last;

            @Override public boolean hasNext() {
                while (bucket <= limit && index >= buckets[bucket].size()) { bucket++; index = 0; }
                return bucket <= limit;
            }

            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return last = buckets[bucket].get(index++);
            }

            @Override public void remove() {
                if (last == null) throw new IllegalStateException();
                long storage = last.getSorterStorage(intrusiveCollectionId);
                // hasNext() may have advanced to another bucket since next(). Remove the returned entry.
                if (storage != 0 && ((int) storage >> 1) == bucket && (int) (storage >>> 32) < index) index--;
                SpatialWatchersSortingList.this.remove(last);
                last = null;
            }
        };
    }

    public void remove(T element) {
        long storage = element.getSorterStorage(intrusiveCollectionId);
        if (storage == 0) return;
        int bucket = (int) storage >> 1, index = (int) (storage >>> 32);
        removeFromBucket(buckets[bucket], index);
        element.setSorterStorage(intrusiveCollectionId, 0);
        size--;
    }

    private void removeFromBucket(ObjectArrayList<T> list, int index) {
        T replacement = list.pop();
        if (index < list.size()) {
            list.set(index, replacement);
            replacement.setSorterStorage(intrusiveCollectionId,
                    (replacement.getSorterStorage(intrusiveCollectionId) & 0xffffffffL) | ((long) index << 32));
        }
    }

    public void removeIf(Predicate<T> predicate) {
        for (Iterator<T> iterator = iterator(); iterator.hasNext();) {
            if (predicate.test(iterator.next())) iterator.remove();
        }
    }

}
