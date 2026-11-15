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
package io.github.opencubicchunks.cubicchunks.core.worldgen;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A terrain preview the host can take back. Samplers made here sample through it. After
 * {@link #revoke} every new column throws {@link TerrainPreviewWithdrawnException}; after
 * {@link #withdraw} returns, no column is being sampled either. The host revokes a client's
 * preview when its world is replaced, and withdraws an integrated server's generator before that
 * server closes the world.
 *
 * <p>Each column holds the read side of a lock and withdrawal takes the write side, so withdrawal
 * waits for the columns in flight and for nothing else. A column costs tens of microseconds and
 * up, so the lock is not what it spends its time on.
 */
public final class WithdrawablePreview implements ICubicTerrainPreview {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ICubicTerrainPreview preview;
    private volatile boolean revoked;

    public WithdrawablePreview(ICubicTerrainPreview preview) {
        this.preview = preview;
    }

    @Override
    public Sampler createTerrainPreviewSampler() {
        lock.readLock().lock();
        try {
            if (revoked) {
                throw new TerrainPreviewWithdrawnException();
            }
            return new Guarded(preview.createTerrainPreviewSampler());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Whether the preview has been taken back. */
    public boolean withdrawn() {
        return revoked;
    }

    /** Refuses every column from now on, without waiting for those being sampled. Safe to repeat. */
    public void revoke() {
        revoked = true;
    }

    /**
     * Refuses every column from now on and waits for those being sampled. Safe to repeat. Must not
     * be called from inside a column of this preview.
     */
    public void withdraw() {
        revoked = true;
        lock.writeLock().lock();
        lock.writeLock().unlock();
    }

    private final class Guarded implements Sampler {
        private final Sampler sampler;

        Guarded(Sampler sampler) {
            this.sampler = sampler;
        }

        @Override
        public void sampleColumn(int blockX, int blockZ, ColumnConsumer consumer) {
            lock.readLock().lock();
            try {
                if (revoked) {
                    throw new TerrainPreviewWithdrawnException();
                }
                sampler.sampleColumn(blockX, blockZ, consumer);
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void sampleColumn(int blockX, int blockZ, int footprint, ColumnConsumer consumer) {
            lock.readLock().lock();
            try {
                if (revoked) {
                    throw new TerrainPreviewWithdrawnException();
                }
                sampler.sampleColumn(blockX, blockZ, footprint, consumer);
            } finally {
                lock.readLock().unlock();
            }
        }
    }
}
