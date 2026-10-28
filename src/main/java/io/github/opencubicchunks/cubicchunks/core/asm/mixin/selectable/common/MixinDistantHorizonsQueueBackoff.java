/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkPosQueue;
import java.util.concurrent.locks.LockSupport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bound retry churn when DH cannot dispatch the closest queued update. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager", remap = false)
public abstract class MixinDistantHorizonsQueueBackoff {
    @Shadow @Final public ChunkPosQueue preUpdateQueue;

    // DH has three retry exits: debounce, unavailable executor, and rejection.
    // Each has already returned its item to the original queue before this hook.
    // Do not pace successful dispatch or delay pre-update work already waiting.
    @Inject(method = "tryDispatchReadyChunkUpdate", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkPosQueue;addItem(Lcom/seibel/distanthorizons/core/pos/DhChunkPos;Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkUpdateData;)V",
            shift = At.Shift.AFTER), require = 3, expect = 3, allow = 3, remap = false)
    private void cubicchunks$pauseFailedDispatch(CallbackInfo callback) {
        if (preUpdateQueue.isEmpty()) {
            // New arrivals wait at most this requested interval plus OS scheduling.
            // park preserves interrupts, so DH's shutdown loop still observes them.
            LockSupport.parkNanos(2_000_000L);
        }
    }
}
