/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client;

import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsClient;
import java.util.ArrayList;
import java.util.Comparator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DH's client quad tree hands its missing sections to the request queue nearest first, until the queue is full. In a
 * cubic world Cubature's arrival plan (ArrivalPlan) chooses that order instead and leaves out the sections it holds
 * back; those stay missing in DH's tree, which offers them again on its next pass.
 *
 * <p>DH's tree finds the sections it newly wants only when its queueing thread starts, and that thread sleeps and
 * retries for as long as anything stays missing. While the queue is full, as in fast flight, it never ends, so terrain
 * the player flies toward is never asked for. In a cubic world the thread ends after half a second without queueing
 * anything, and DH's next tree update starts it again with the sections wanted now.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree", remap = false)
public abstract class MixinDistantHorizonsArrivalTree {
    @Redirect(method = "startQueuedRetrievalTasks", at = @At(value = "INVOKE",
            target = "Ljava/util/ArrayList;sort(Ljava/util/Comparator;)V"), require = 1, allow = 1, remap = false)
    private void cubicchunks$arrivalOrder(ArrayList<Long> missing, Comparator<? super Long> nearestFirst) {
        if (!DistantHorizonsClient.orderByArrival(missing)) missing.sort(nearestFirst);
    }

    @Unique private long cubicchunks$idleSince;

    /** DH's queueing thread, idle: its InterruptedException handler ends the thread. */
    @Redirect(method = "lambda$updateAllRenderSections$6", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V"),
            require = 1, allow = 1, remap = false)
    private void cubicchunks$endWhenIdle(long millis) throws InterruptedException {
        if (DistantHorizonsClient.refreshesWanted()) {
            long now = System.nanoTime();
            if (cubicchunks$idleSince == 0) cubicchunks$idleSince = now;
            else if (now - cubicchunks$idleSince >= 500_000_000L) {
                cubicchunks$idleSince = 0;
                throw new InterruptedException("Cubature: find the sections DH wants now");
            }
        }
        Thread.sleep(millis);
    }
}
