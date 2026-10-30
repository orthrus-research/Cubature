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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Native view first: while Cubature's preview generator holds new tiles (its world's native view is behind, at a
 * join or a teleport), DH's world generation queue finds its generator busy, so no preview tile starts and those
 * running finish. DH still picks which tile comes next once it opens, and its queueing loop asks again each tick.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue", remap = false)
public abstract class MixinDistantHorizonsNativeFirst {
    @Shadow @Final private IDhApiWorldGenerator generator;

    @Inject(method = "isGeneratorBusy", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void cubicchunks$holdForNativeView(CallbackInfoReturnable<Boolean> busy) {
        if (generator instanceof PreviewAdmission && ((PreviewAdmission) generator).holdsNewTiles()) busy.setReturnValue(true);
    }
}
