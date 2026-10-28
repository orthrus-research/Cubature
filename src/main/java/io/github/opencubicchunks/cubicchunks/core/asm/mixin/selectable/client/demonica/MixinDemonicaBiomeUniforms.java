/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Demonica 0.9.0 (its Iris shader pipeline).
 * No Demonica code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.demonica;

import io.github.opencubicchunks.cubicchunks.core.compat.demonica.DemonicaCompat;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Give shader packs the biome at the camera's height in a cubic world, where biomes vary by Y. */
@Pseudo
@Mixin(targets = "net.coderbot.iris.uniforms.BiomeUniforms", remap = false)
public abstract class MixinDemonicaBiomeUniforms {
    // Demonica reads the biome at Y 0 under the player and caches it by X and Z, so a cubic world's
    // per-cube 3D biomes never reached biome, biome_category, temperature or rainfall.
    @Inject(method = "getCachedBiome", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void cubicchunks$cameraBiome(CallbackInfoReturnable<Biome> cir) {
        Biome biome = DemonicaCompat.cameraBiome();
        if (biome != null) {
            cir.setReturnValue(biome);
        }
    }
}
