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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class TestWithdrawablePreview {

    /** A preview whose columns count themselves and can be held inside a column. */
    private static final class Counting implements ICubicTerrainPreview {
        final AtomicInteger columns = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean hold;

        @Override public Sampler createTerrainPreviewSampler() {
            return (x, z, consumer) -> {
                entered.countDown();
                if (hold) {
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                columns.incrementAndGet();
            };
        }
    }

    @Test public void samplesThroughUntilRevokedThenRefusesColumnsAndSamplers() {
        Counting inner = new Counting();
        WithdrawablePreview preview = new WithdrawablePreview(inner);
        ICubicTerrainPreview.Sampler sampler = preview.createTerrainPreviewSampler();
        sampler.sampleColumn(1, 2, null);
        sampler.sampleColumn(1, 2, 16, null);
        assertEquals(2, inner.columns.get());
        assertFalse(preview.withdrawn());

        preview.revoke();
        assertTrue(preview.withdrawn());
        try {
            sampler.sampleColumn(1, 2, null);
            fail("A revoked preview sampled a column");
        } catch (TerrainPreviewWithdrawnException expected) {
            // The column in hand is dropped.
        }
        try {
            sampler.sampleColumn(1, 2, 16, null);
            fail("A revoked preview sampled a coarse column");
        } catch (TerrainPreviewWithdrawnException expected) {
            // The column in hand is dropped.
        }
        try {
            preview.createTerrainPreviewSampler();
            fail("A revoked preview made a sampler");
        } catch (TerrainPreviewWithdrawnException expected) {
            // No sampler for a preview that is gone.
        }
        assertEquals(2, inner.columns.get());
    }

    @Test(timeout = 20000) public void withdrawalWaitsForTheColumnInFlightAndRevocationDoesNot() throws Exception {
        Counting inner = new Counting();
        inner.hold = true;
        WithdrawablePreview preview = new WithdrawablePreview(inner);
        ICubicTerrainPreview.Sampler sampler = preview.createTerrainPreviewSampler();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sampling = new Thread(() -> {
            try {
                sampler.sampleColumn(0, 0, null);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        sampling.start();
        assertTrue(inner.entered.await(10, TimeUnit.SECONDS));

        // Revoking returns at once while the column is still inside the generator.
        preview.revoke();
        assertEquals(0, inner.columns.get());

        CountDownLatch withdrawn = new CountDownLatch(1);
        Thread withdrawing = new Thread(() -> {
            preview.withdraw();
            withdrawn.countDown();
        });
        withdrawing.start();
        assertFalse("Withdrawal returned while a column was being sampled",
                withdrawn.await(300, TimeUnit.MILLISECONDS));

        inner.release.countDown();
        assertTrue(withdrawn.await(10, TimeUnit.SECONDS));
        sampling.join();
        withdrawing.join();
        // The column that was in flight finished; nothing ran after the withdrawal.
        assertNull(failure.get());
        assertEquals(1, inner.columns.get());
        preview.withdraw();
    }

    @Test public void aWorldLendsUntilItClosesAndAServerUntilItStops() {
        Object world = new Object(), other = new Object();
        Counting generator = new Counting();
        SharedTerrainPreviews.serverStarting();
        WithdrawablePreview first = SharedTerrainPreviews.lend(world, generator);
        WithdrawablePreview second = SharedTerrainPreviews.lend(world, generator);
        WithdrawablePreview elsewhere = SharedTerrainPreviews.lend(other, generator);
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(elsewhere);

        SharedTerrainPreviews.giveBack(second);
        assertTrue(second.withdrawn());
        assertFalse(first.withdrawn());

        SharedTerrainPreviews.withdraw(world);
        assertTrue(first.withdrawn());
        assertFalse(elsewhere.withdrawn());
        assertNull("A closing world lent its generator", SharedTerrainPreviews.lend(world, generator));

        SharedTerrainPreviews.withdrawAll();
        assertTrue(elsewhere.withdrawn());
        assertNull("A stopping server lent a generator", SharedTerrainPreviews.lend(other, generator));

        SharedTerrainPreviews.serverStarting();
        WithdrawablePreview next = SharedTerrainPreviews.lend(new Object(), generator);
        assertNotNull(next);
        SharedTerrainPreviews.withdrawAll();
        SharedTerrainPreviews.serverStarting();
    }
}
