package io.github.opencubicchunks.cubicchunks.core.server;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Owner-thread coordinate queue; no mutable world object survives between ticks. */
final class PacedAutosave<K> {
    private final Set<K> pending = new LinkedHashSet<>();
    void add(K key) { pending.add(key); }
    int size() { return pending.size(); }
    void clear() { pending.clear(); }

    int tick(int limit, long nanos, BooleanSupplier canSave, Consumer<K> save, LongSupplier clock) {
        long start = clock.getAsLong();
        int processed = 0;
        while (!pending.isEmpty() && processed < limit && canSave.getAsBoolean()
                && (processed == 0 || clock.getAsLong() - start < nanos)) {
            Iterator<K> iterator = pending.iterator();
            K key = iterator.next();
            // Leave the key queued if serialization fails.
            save.accept(key);
            pending.remove(key);
            processed++;
        }
        return processed;
    }
}
