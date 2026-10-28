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

import io.github.opencubicchunks.cubicchunks.api.util.XZAddressable;
import net.minecraft.entity.player.EntityPlayer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import java.util.Collection;
import java.util.function.Supplier;

/** 2D watcher priorities with shared intrusive bucket management. */
public class WatchersSortingList2D<T extends BucketSorterEntry & XZAddressable> extends SpatialWatchersSortingList<T> {
    private static final int BUCKET_COUNT = (int) (CubicChunks.MAX_RENDER_DISTANCE * Math.sqrt(2)) + 1;
    public WatchersSortingList2D(int intrusiveCollectionId, Supplier<Collection<EntityPlayer>> playersSupplier) {
        super(intrusiveCollectionId, playersSupplier, 2);
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
        long z = element.getZ();

        long dx = x - playerPositions[0];
        long dz = z - playerPositions[1];
        // Note: for the specific case of 2d we can just about ignore large deltas triggering an overflow -
        // only happens when both deltas are Integer.MIN_VALUE which is not a case we support
        long distSqMin = dx*dx + dz*dz;
        for (int i = 2; i < playerPositions.length; i += 2) {
            dx = x - playerPositions[i];
            dz = z - playerPositions[i+1];
            long distSq = dx*dx + dz*dz;
            if (distSq < distSqMin) {
                distSqMin = distSq;
            }
        }
        // fast very approximate square root
        int log2dist = 64 - Long.numberOfLeadingZeros(distSqMin);
        int bitsToCutOff = log2dist >> 1;
        long approxDist = distSqMin >> bitsToCutOff;
        return (int) Math.min(approxDist, BUCKET_COUNT - 1);
    }

}
