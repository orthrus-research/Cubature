/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
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
package io.github.opencubicchunks.cubicchunks.core.client.source;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The cube events of one client tick, in the order they happened. Changes to one cube are merged
 * into one event, a change to a cube whose arrival is already queued is absorbed by it, and a change
 * at a cube's boundary, an arrival or a removal also raises a change for each loaded cube that
 * touches it there.
 */
final class CubeEventQueue {

    /** Tells whether a cube is loaded now. */
    interface Loaded {
        boolean test(int cubeX, int cubeY, int cubeZ);
    }

    private static final int LOADED = 0;
    private static final int UNLOADED = 1;
    private static final int CHANGED = 2;

    private static final class Event {
        final int type;
        final int x;
        final int y;
        final int z;
        int kinds;
        int minX = 15, minY = 15, minZ = 15, maxX, maxY, maxZ;

        Event(int type, int x, int y, int z) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Key {
        final int x;
        final int y;
        final int z;

        Key(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key && ((Key) other).x == x && ((Key) other).y == y && ((Key) other).z == z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }
    }

    private final List<Event> events = new ArrayList<>();
    /** The queued arrival or change of each cube that later changes still merge into. */
    private final Map<Key, Event> open = new HashMap<>();

    boolean isEmpty() {
        return events.isEmpty();
    }

    void clear() {
        events.clear();
        open.clear();
    }

    void loaded(int x, int y, int z, Loaded loaded) {
        Event event = new Event(LOADED, x, y, z);
        events.add(event);
        open.put(new Key(x, y, z), event);
        neighbors(x, y, z, 0, 0, 0, 15, 15, 15, loaded);
    }

    void unloaded(int x, int y, int z, Loaded loaded) {
        events.add(new Event(UNLOADED, x, y, z));
        open.remove(new Key(x, y, z));
        neighbors(x, y, z, 0, 0, 0, 15, 15, 15, loaded);
    }

    /** A change within a loaded cube; the bounds are local, 0 to 15, inclusive. */
    void changed(int x, int y, int z, int kinds, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Loaded loaded) {
        if (merge(x, y, z, kinds, minX, minY, minZ, maxX, maxY, maxZ)) {
            neighbors(x, y, z, minX, minY, minZ, maxX, maxY, maxZ, loaded);
        }
    }

    /** @return false when a queued arrival of the cube already covers the change */
    private boolean merge(int x, int y, int z, int kinds, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ) {
        Key key = new Key(x, y, z);
        Event event = open.get(key);
        if (event != null && event.type == LOADED) {
            return false;
        }
        if (event == null) {
            event = new Event(CHANGED, x, y, z);
            events.add(event);
            open.put(key, event);
        }
        event.kinds |= kinds;
        event.minX = Math.min(event.minX, minX);
        event.minY = Math.min(event.minY, minY);
        event.minZ = Math.min(event.minZ, minZ);
        event.maxX = Math.max(event.maxX, maxX);
        event.maxY = Math.max(event.maxY, maxY);
        event.maxZ = Math.max(event.maxZ, maxZ);
        return true;
    }

    private void neighbors(int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Loaded loaded) {
        for (int dx = -1; dx <= 1; dx++) {
            if (!touches(dx, minX, maxX)) {
                continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
                if (!touches(dy, minY, maxY)) {
                    continue;
                }
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) != 0 && touches(dz, minZ, maxZ) && loaded.test(x + dx, y + dy, z + dz)) {
                        merge(x + dx, y + dy, z + dz, ICubeSourceListener.NEIGHBOR, 0, 0, 0, 15, 15, 15);
                    }
                }
            }
        }
    }

    /** Whether bounds along one axis reach the cube's face in the given direction. */
    private static boolean touches(int direction, int min, int max) {
        return direction == 0 || (direction < 0 ? min == 0 : max == 15);
    }

    /** Delivers the queued events in order and empties the queue. */
    void drain(ICubeSourceListener listener) {
        // Delivered from a copy: a listener may cause further events, which belong to the next drain.
        List<Event> batch = new ArrayList<>(events);
        clear();
        for (Event event : batch) {
            switch (event.type) {
                case LOADED:
                    listener.cubeLoaded(event.x, event.y, event.z);
                    break;
                case UNLOADED:
                    listener.cubeUnloaded(event.x, event.y, event.z);
                    break;
                default:
                    listener.cubeChanged(event.x, event.y, event.z, event.kinds, event.minX, event.minY, event.minZ,
                            event.maxX, event.maxY, event.maxZ);
                    break;
            }
        }
    }
}
