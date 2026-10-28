/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.celeritas;

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.compat.celeritas.CeleritasCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Start Celeritas's build height at the cubic world's floor, so entities below Y 0 are culled too. */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer")
public abstract class MixinCeleritasWorldRenderer {
    // Celeritas returns 0 and isBoxVisible counts any box outside the build height as visible, so in a cubic world
    // every entity below Y 0 skipped its section-occlusion test and drew even behind solid rock. The renderer always
    // draws the client's current world.
    @Inject(method = "getMinimumBuildHeight", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void cubicchunks$cubicFloor(CallbackInfoReturnable<Integer> cir) {
        WorldClient world = Minecraft.getMinecraft().world;
        if (CeleritasCompat.cubic(world)) {
            cir.setReturnValue(((ICubicWorld) world).getMinHeight());
        }
    }
}
