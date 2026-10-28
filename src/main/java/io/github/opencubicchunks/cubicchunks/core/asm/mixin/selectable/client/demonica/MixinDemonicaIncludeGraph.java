/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Demonica 0.9.0 (its Iris shader pipeline).
 * No Demonica code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.demonica;

import java.nio.file.Path;

import io.github.opencubicchunks.cubicchunks.core.compat.demonica.DemonicaCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hand each shader pack file Demonica reads to Cubature's opt-in pack patches (complementaryCaveFog). */
@Pseudo
@Mixin(targets = "net.coderbot.iris.shaderpack.include.IncludeGraph", remap = false)
public abstract class MixinDemonicaIncludeGraph {
    // Every file of a pack's include graph is read through this one method, zip or folder alike.
    @Inject(method = "readFile", at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private static void cubicchunks$patchPackSource(Path path, CallbackInfoReturnable<String> cir) {
        String patched = DemonicaCompat.patchPackSource(path, cir.getReturnValue());
        if (patched != null) {
            cir.setReturnValue(patched);
        }
    }
}
