/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsClient;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH's client sends the nearest waiting section request first, keeping up to its request limit in flight. For the
 * world generation queue of a cubic world, Cubature's arrival plan chooses which waiting request goes next, cancels
 * the unsent ones it now holds back (DH's tree keeps them missing and offers them again) and keeps in flight only what
 * the server answers in about 3 s. DH's sync-on-load queue keeps DH's order and limit.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue", remap = false)
public abstract class MixinDistantHorizonsArrivalRequests {
    @Shadow protected abstract int getRequestRateLimit();

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/multiplayer/client/AbstractFullDataNetworkRequestQueue;getRequestRateLimit()I"),
            require = 1, allow = 1, remap = false)
    private int cubicchunks$inflight(AbstractFullDataNetworkRequestQueue queue, Operation<Integer> original) {
        int limit = original.call(queue);
        return DistantHorizonsClient.generationQueue(this) ? DistantHorizonsClient.inflight(limit) : limit;
    }

    @Inject(method = "submitRequest", at = @At("HEAD"), require = 1, remap = false)
    private void cubicchunks$queued(long pos, Long timestamp, CallbackInfoReturnable<?> result) {
        if (DistantHorizonsClient.generationQueue(this)) DistantHorizonsClient.requestQueued(pos);
    }

    @WrapOperation(method = "sendNextRequest", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;min(Ljava/util/Comparator;)Ljava/util/Optional;"), require = 1, allow = 1, remap = false)
    private Optional<Object> cubicchunks$next(Stream<Object> waiting, Comparator<Object> nearestFirst, Operation<Optional<Object>> original) {
        return DistantHorizonsClient.generationQueue(this) ? DistantHorizonsClient.nextRequest(waiting, nearestFirst)
                : original.call(waiting, nearestFirst);
    }

    @Inject(method = "handleNetResponse", at = @At("HEAD"), require = 1, remap = false)
    private void cubicchunks$answered(@Coerce Object task, @Coerce Object response, Throwable failure, CallbackInfo info) {
        if (failure == null && response != null && DistantHorizonsClient.generationQueue(this)) DistantHorizonsClient.requestAnswered(task);
    }
}
