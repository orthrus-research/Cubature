/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.worldgen.IRetainedColumnPreparation;
import io.github.opencubicchunks.cubicchunks.api.worldgen.IRetainedColumnPreparation.ColumnLease;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * Arrival-only preparation on the existing generation executor. Active column keys, including
 * abandoned jobs still running, stay within capacity. Waiting requests are owned by held arrivals.
 * A prepared lease and a failed result remain until the last owner releases the key: neither can be
 * evicted into synchronous generation, and failure never starts a retry by itself.
 */
final class ArrivalColumnPreparation {
    enum State { PENDING, READY, FAILED }

    record Status(State state, String detail) { }

    private static final class Entry {
        final long key;
        int references;
        State state = State.PENDING;
        String detail = "arrival column preparation is queued or running";
        @Nullable ColumnLease lease;

        Entry(long key, int references) {
            this.key = key;
            this.references = references;
        }
    }

    private final Executor executor;
    private final IRetainedColumnPreparation generator;
    private final int capacity;
    // Guarded by this. Stable owner order also makes waiting-key admission stable.
    private final Map<UUID, Long> owners = new LinkedHashMap<>();
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    ArrivalColumnPreparation(Executor executor, IRetainedColumnPreparation generator, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Arrival preparation capacity must be positive");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.capacity = capacity;
    }

    /**
     * Retains an explicitly requested destination. Repeating an existing owner's failed request
     * retries that shared key once; pending and successful requests remain retained unchanged.
     */
    void retain(UUID owner, int columnX, int columnZ) {
        List<ColumnLease> release = new ArrayList<>();
        List<Entry> start;
        long key = key(columnX, columnZ);
        synchronized (this) {
            if (closed) return;
            Long previous = owners.get(owner);
            if (previous != null && previous == key) {
                Entry entry = entries.get(key);
                if (entry == null || entry.state != State.FAILED) return;
                // Only an owner repeating its own request can retry; joining owners do not.
                Entry retry = new Entry(key, entry.references);
                entries.put(key, retry);
                start = List.of(retry);
            } else {
                if (previous != null) detach(owner, release);
                owners.put(owner, key);
                Entry entry = entries.get(key);
                if (entry != null) entry.references++;
                start = admit();
            }
        }
        closeLeases(release);
        submit(start);
    }

    /** Releases an owner; a running orphan keeps its slot until its worker finishes. */
    void release(UUID owner) {
        List<ColumnLease> release = new ArrayList<>();
        List<Entry> start;
        synchronized (this) {
            detach(owner, release);
            start = admit();
        }
        closeLeases(release);
        submit(start);
    }

    /** Reads a state without submitting work, spending a success or retrying a failure. */
    synchronized Status status(int columnX, int columnZ) {
        if (closed) return new Status(State.FAILED, "arrival column preparation is closed");
        long key = key(columnX, columnZ);
        Entry entry = entries.get(key);
        if (entry != null && entry.references > 0) return new Status(entry.state, entry.detail);
        return new Status(State.PENDING, owners.containsValue(key)
                ? "waiting for arrival preparation capacity" : "arrival column is not requested");
    }

    /** Stops admission and releases successes; late workers release their own abandoned lease. */
    void close() {
        List<ColumnLease> release = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            owners.clear();
            for (Entry entry : entries.values()) {
                entry.references = 0;
                if (entry.lease != null) release.add(entry.lease);
                entry.lease = null;
            }
            // shutdownNow may discard queued tasks without ever calling finish. Running tasks
            // observe closed when they return and release their own late result.
            entries.clear();
        }
        closeLeases(release);
    }

    synchronized int retained() { return entries.size(); }

    synchronized int running() {
        int count = 0;
        for (Entry entry : entries.values()) if (entry.state == State.PENDING) count++;
        return count;
    }

    /** Under the monitor: remove an owner, disposing only terminal unowned entries. */
    private void detach(UUID owner, List<ColumnLease> release) {
        Long key = owners.remove(owner);
        if (key == null) return;
        Entry entry = entries.get(key);
        if (entry == null) return;
        if (--entry.references == 0 && entry.state != State.PENDING) {
            entries.remove(key);
            if (entry.lease != null) release.add(entry.lease);
            entry.lease = null;
        }
    }

    /** Under the monitor: admit only missing requested keys while a bounded slot is free. */
    private List<Entry> admit() {
        List<Entry> start = new ArrayList<>();
        if (closed) return start;
        for (long key : owners.values()) {
            if (entries.size() >= capacity) break;
            if (entries.containsKey(key)) continue;
            int references = 0;
            for (long requested : owners.values()) if (requested == key) references++;
            Entry entry = new Entry(key, references);
            entries.put(key, entry);
            start.add(entry);
        }
        return start;
    }

    private void submit(List<Entry> start) {
        for (Entry entry : start) {
            try {
                executor.execute(() -> prepare(entry));
            } catch (RuntimeException | Error failure) {
                finish(entry, null, failure);
            }
        }
    }

    private void prepare(Entry entry) {
        ColumnLease lease = null;
        Throwable failure = null;
        try {
            lease = Objects.requireNonNull(generator.prepareRetainedColumn((int) (entry.key >> 32), (int) entry.key),
                    "Retained column preparation returned no lease");
        } catch (RuntimeException | Error failed) {
            failure = failed;
        }
        finish(entry, lease, failure);
    }

    private void finish(Entry entry, @Nullable ColumnLease lease, @Nullable Throwable failure) {
        List<Entry> start;
        boolean abandoned;
        synchronized (this) {
            abandoned = closed || entry.references == 0;
            if (abandoned) entries.remove(entry.key, entry);
            else {
                entry.lease = lease;
                entry.state = failure == null ? State.READY : State.FAILED;
                entry.detail = failure == null ? "arrival column data is retained"
                        : "arrival column preparation failed: " + failure.getClass().getSimpleName();
            }
            start = admit();
        }
        if (failure != null)
            CubicChunks.LOGGER.error("Arrival column preparation failed at ({}, {})",
                    (int) (entry.key >> 32), (int) entry.key, failure);
        if (abandoned && lease != null) closeLease(lease);
        submit(start);
    }

    private static void closeLeases(List<ColumnLease> leases) {
        for (ColumnLease lease : leases) closeLease(lease);
    }

    private static void closeLease(ColumnLease lease) {
        try {
            lease.close();
        } catch (RuntimeException | Error failure) {
            CubicChunks.LOGGER.error("Arrival column retention could not be released", failure);
        }
    }

    private static long key(int columnX, int columnZ) {
        return ((long) columnX << 32) ^ (columnZ & 0xffffffffL);
    }
}
