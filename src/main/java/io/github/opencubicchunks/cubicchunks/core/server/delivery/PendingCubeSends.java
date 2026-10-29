package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;

/** Owner-thread FIFO per player, with round-robin service and deduplication that preserves age. */
final class PendingCubeSends<P, C> {
    // Entity equality reuses IDs across respawns; a deferred queue belongs to the actual connection/player instance.
    private final Reference2ObjectLinkedOpenHashMap<P, Object2LongLinkedOpenHashMap<C>> players = new Reference2ObjectLinkedOpenHashMap<>(2);
    private int size;

    void add(P player, C cube, long now) {
        Object2LongLinkedOpenHashMap<C> queue = players.get(player);
        if (queue == null) {
            queue = new Object2LongLinkedOpenHashMap<>(16);
            players.put(player, queue);
        }
        if (!queue.containsKey(cube)) {
            queue.put(cube, now);
            size++;
        }
    }

    boolean remove(P player, C cube) {
        Object2LongLinkedOpenHashMap<C> queue = players.get(player);
        if (queue == null || !queue.containsKey(cube)) return false;
        queue.removeLong(cube);
        size--;
        if (queue.isEmpty()) players.remove(player);
        return true;
    }

    void removePlayer(P player) {
        Object2LongLinkedOpenHashMap<C> queue = players.remove(player);
        if (queue != null) size -= queue.size();
    }

    P firstPlayer() { return players.isEmpty() ? null : players.firstKey(); }

    C firstCube(P player) {
        Object2LongLinkedOpenHashMap<C> queue = players.get(player);
        return queue == null ? null : queue.firstKey();
    }

    void rotate(P player) { if (players.containsKey(player)) players.getAndMoveToLast(player); }
    boolean isEmpty() { return size == 0; }
    int size() { return size; }

    long oldestAge(long now) {
        long age = 0;
        for (Object2LongLinkedOpenHashMap<C> queue : players.values()) {
            age = Math.max(age, now - queue.getLong(queue.firstKey()));
        }
        return age;
    }
}
