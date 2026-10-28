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
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Give a ready cubic column render sections only for its loaded cubes. */
@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager")
public abstract class MixinCeleritasRenderSectionManager {
    @Shadow(remap = false) public abstract void onSectionAdded(int x, int y, int z);

    // Celeritas adds a section for every section of the world's height when a column becomes ready: 128 per column
    // in a world from Y-1664, where the client holds about 25 cubes per column. Every section costs per-frame work,
    // and unloaded ones read as empty, so its occlusion search could also wander far past the loaded cubes. Cubes
    // that arrive later add their own section (CeleritasCompat.cubeLoaded); onChunkRemoved still sweeps the whole
    // height, and removing an absent section does nothing.
    @Inject(method = "onChunkAdded", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void cubicchunks$loadedCubesOnly(int x, int z, CallbackInfo ci) {
        WorldClient world = Minecraft.getMinecraft().world;
        if (CeleritasCompat.cubic(world)) {
            for (int y : CeleritasCompat.loadedCubeYs(world, x, z)) {
                onSectionAdded(x, y, z);
            }
            ci.cancel();
        }
    }
}
