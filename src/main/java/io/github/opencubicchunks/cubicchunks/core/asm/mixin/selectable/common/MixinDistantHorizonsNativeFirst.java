/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.PreviewAdmission;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cubature decides when DH's world generation queue may start a cubic preview tile; DH still picks which. While
 * Cubature's preview generator holds new tiles (its world's native view is behind, at a join or a teleport), the queue
 * finds the generator busy, so no tile starts and those running finish. With Cubature's preview pool, the pool's
 * permits replace DH's thread count: at most the permits plus one tile are in progress, the one waiting for the next
 * free permit, so DH's choice stays current. DH's queueing loop asks again each tick and after each tile.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue", remap = false)
public abstract class MixinDistantHorizonsNativeFirst {
    @Shadow @Final private IDhApiWorldGenerator generator;
    @Shadow @Final private ConcurrentHashMap<Long, ?> inProgressGenTasksByLodPos;

    @Inject(method = "isGeneratorBusy", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void cubicchunks$admit(CallbackInfoReturnable<Boolean> busy) {
        if (!(generator instanceof PreviewAdmission)) return;
        PreviewAdmission admission = (PreviewAdmission) generator;
        if (admission.holdsNewTiles()) busy.setReturnValue(true);
        else if (admission.pooled()) busy.setReturnValue(!admission.admits(inProgressGenTasksByLodPos.size()));
    }
}
