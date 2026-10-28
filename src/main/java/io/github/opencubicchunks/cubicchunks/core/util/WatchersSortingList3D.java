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

import io.github.opencubicchunks.cubicchunks.api.util.XYZAddressable;
import net.minecraft.entity.player.EntityPlayer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import java.util.Collection;
import java.util.function.Supplier;

/** 3D watcher priorities with shared intrusive bucket management. */
public class WatchersSortingList3D<T extends BucketSorterEntry & XYZAddressable> extends SpatialWatchersSortingList<T> {
    private static final int BUCKET_COUNT = (int) (CubicChunks.MAX_RENDER_DISTANCE * Math.sqrt(3)) + 1;
    public WatchersSortingList3D(int intrusiveCollectionId, Supplier<Collection<EntityPlayer>> playersSupplier) {
        super(intrusiveCollectionId, playersSupplier, 3);
    }

    @Override public void add(T element) {
        if (contains(element)) return;
        int bucket = computeBucketIdx(element);
        ObjectArrayList<T> list = buckets[bucket];
        int index = list.size();
        list.add(element);
        element.setSorterStorage(intrusiveCollectionId, ((long) index << 32) | ((long) bucket << 1) | 1L);
        size++;
    }

    @Override public void redistributeBucket(int bucket) {
        ObjectArrayList<T> list = buckets[bucket];
        for (int i = 0; i < list.size();) {
            T element = list.get(i);
            int target = computeBucketIdx(element);
            if (target == bucket) { i++; continue; }
            T replacement = list.pop();
            if (i < list.size()) {
                list.set(i, replacement);
                long data = replacement.getSorterStorage(intrusiveCollectionId) & 0xffffffffL;
                replacement.setSorterStorage(intrusiveCollectionId, data | ((long) i << 32));
            }
            ObjectArrayList<T> destination = buckets[target];
            int index = destination.size();
            destination.add(element);
            element.setSorterStorage(intrusiveCollectionId, ((long) index << 32) | ((long) target << 1) | 1L);
        }
    }

    private int computeBucketIdx(T element) {
        if (playerPositions.length == 0) {
            return BUCKET_COUNT - 1;
        }
        // Note: don't care about performance on 32-bit JVM, nobody should be using that in 2025+
        long x = element.getX();
        long y = element.getY();
        long z = element.getZ();

        long dx = x - playerPositions[0];
        long dy = y - playerPositions[1];
        long dz = z - playerPositions[2];
        long dx2 = dx * dx;
        long dy2 = dy * dy;
        long dz2 = dz * dz;
        long masked = dx2 | dy2 | dz2;
        // Note: we can't just rely on this being long here, Integer.MAX_VALUE * Integer.MAX_VALUE * 3 still overflows a long
        long distSqMin = masked > (long) Integer.MAX_VALUE ? Integer.MAX_VALUE : dx2 + dy2 + dz2;

        for (int i = 3; i < playerPositions.length; i += 3) {
            dx = x - playerPositions[i];
            dy = y - playerPositions[i+1];
            dz = z - playerPositions[i+2];
            dx2 = dx * dx;
            dy2 = dy * dy;
            dz2 = dz * dz;
            masked = dx2 | dy2 | dz2;
            long distSq = masked > (long) Integer.MAX_VALUE ? Integer.MAX_VALUE : dx2 + dy2 + dz2;
            if (distSq < distSqMin) {
                distSqMin = distSq;
            }
        }
        // fast very approximate square root
        int log2dist = 64 - Long.numberOfLeadingZeros(distSqMin);
        int bitsToCutOff = log2dist >> 1;
        long approxDist = distSqMin >> bitsToCutOff;
        int min = (int) Math.min(approxDist, BUCKET_COUNT - 1);
        return min;
    }

}
