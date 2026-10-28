/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.celeritas;

import io.github.opencubicchunks.cubicchunks.core.compat.celeritas.CeleritasCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply cube loads and unloads to Celeritas's sections where it applies its own column events. */
@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer")
public abstract class MixinCeleritasChunkEvents {
    // setupTerrain finishes any graph search before processChunkEvents, so sections can be added and removed here.
    @Inject(method = "processChunkEvents", at = @At("TAIL"), require = 1, remap = false)
    private void cubicchunks$applyCubeChanges(CallbackInfo ci) {
        CeleritasCompat.applyCubeChanges(Minecraft.getMinecraft().world);
    }
}
