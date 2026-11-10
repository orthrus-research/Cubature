/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.api.worldgen;

/**
 * Optional preparation for arrivals that must not rebuild an evicted column on the server thread.
 * The host prepares on its generation workers and retains the returned lease until the destination
 * column has been installed, or the last arrival needing it has ended.
 *
 * <p>Implementations are pure and thread-safe, do not touch a live world, and use only the executor
 * the host supplies for their own parallel work. Preparation and retention may change when content
 * exists, never what the generator produces.
 */
public interface IRetainedColumnPreparation {
    /**
     * Prepares all immutable data the generator's normal column generation will need and keeps that
     * data reachable independently of its evictable caches. Until the lease closes, generating this
     * column must use the retained data rather than rebuild it. A failed preparation throws; it must
     * not return null or leave a lease the host cannot release.
     *
     * @param columnX chunk-column X
     * @param columnZ chunk-column Z
     * @return the host-owned retention of this column's prepared data
     */
    ColumnLease prepareRetainedColumn(int columnX, int columnZ);

    /** Retention ends on the host's thread or on a worker completing an abandoned preparation. */
    interface ColumnLease extends AutoCloseable {
        /** Releases this lease once; implementations must allow concurrent leases of the same key. */
        @Override void close();
    }
}
