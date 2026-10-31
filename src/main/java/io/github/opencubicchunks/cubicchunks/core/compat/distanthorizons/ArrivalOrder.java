/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The order in which section requests reached a server's DH generation queue. Clients send their requests in the
 * order they want them (ArrivalPlan), so the server starts the earliest waiting request instead of the nearest to one
 * player. A section asked for again while it waits keeps its first place; once started, it is forgotten.
 */
public final class ArrivalOrder {
    private final ConcurrentHashMap<Long, Long> stamps = new ConcurrentHashMap<>();
    private final AtomicLong next = new AtomicLong();

    /** A request for a section reached the queue. */
    public void arrived(long pos) { stamps.putIfAbsent(pos, next.getAndIncrement()); }

    /** The waiting entry that arrived first, now forgotten; a waiting entry never seen arrives now. Null if none wait. */
    public <V> Map.Entry<Long, V> first(Map<Long, V> waiting) {
        Map.Entry<Long, V> best = null;
        long bestStamp = Long.MAX_VALUE;
        for (Map.Entry<Long, V> entry : waiting.entrySet()) {
            Long stamp = stamps.get(entry.getKey());
            if (stamp == null) {
                arrived(entry.getKey());
                stamp = stamps.get(entry.getKey());
            }
            if (stamp != null && stamp < bestStamp) {
                best = entry;
                bestStamp = stamp;
            }
        }
        if (best != null) stamps.remove(best.getKey());
        if (stamps.size() > 2 * waiting.size() + 256) stamps.keySet().retainAll(waiting.keySet());
        return best;
    }

    int tracked() { return stamps.size(); }
}
