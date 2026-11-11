/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.ArrivalOrder;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.PreviewAdmission;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH's world generation queue starts the waiting tile nearest one player (distance over detail level). For Cubature's
 * cubic preview generator it starts the waiting tile whose request arrived first (ArrivalOrder): each client sends
 * its requests in the order its arrival plan wants them, and nearest first would undo that. DH still builds the task
 * it starts, through its own distance function, and decides splitting and merging. Off with
 * {@code distantHorizonsArrivalPlan}.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue", remap = false)
public abstract class MixinDistantHorizonsServeInOrder {
    @Shadow @Final private IDhApiWorldGenerator generator;
    @Unique private final ArrivalOrder cubicchunks$order = new ArrivalOrder();

    @Unique private boolean cubicchunks$inOrder() {
        return CubicChunksConfig.distantHorizonsArrivalPlan && generator instanceof PreviewAdmission;
    }

    @Inject(method = "submitRetrievalTask", at = @At("HEAD"), require = 1, remap = false)
    private void cubicchunks$arrived(long pos, byte requiredDataDetail, CallbackInfoReturnable<?> result) {
        if (cubicchunks$inOrder()) cubicchunks$order.arrived(pos);
    }

    @WrapOperation(method = "tryStartNextWorldGenTask", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ConcurrentHashMap;reduceEntries(JLjava/util/function/Function;Ljava/util/function/BiFunction;)Ljava/lang/Object;"),
            require = 1, allow = 1, remap = false)
    private Object cubicchunks$firstArrived(ConcurrentHashMap<Long, Object> waiting, long parallelism, Function<Map.Entry<Long, Object>, Object> distance, BiFunction<Object, Object, Object> closest, Operation<Object> original) {
        if (!cubicchunks$inOrder()) return original.call(waiting, parallelism, distance, closest);
        Map.Entry<Long, Object> first = cubicchunks$order.first(waiting);
        return first == null ? null : distance.apply(first);
    }
}
