/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.celeritas;

import io.github.opencubicchunks.cubicchunks.core.compat.celeritas.CeleritasCompat;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Give Celeritas's section manager the cubic world's height and answer section emptiness from cubes. */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.terrain.VintageRenderSectionManager")
public abstract class MixinCeleritasSectionManager {
    @Shadow(remap = false) @Final private WorldClient world;

    // create() passes the vanilla range, sections [0, 16), to the manager and its section graph.
    @ModifyConstant(method = "create", constant = @Constant(intValue = 0), require = 1, allow = 1, remap = false)
    private static int cubicchunks$minSection(int vanilla, ChunkVertexType vertexType, WorldClient world,
            int renderDistance, CommandList commandList) {
        return CeleritasCompat.cubic(world) ? CeleritasCompat.minSection(world) : vanilla;
    }

    @ModifyConstant(method = "create", constant = @Constant(intValue = 16), require = 1, allow = 1, remap = false)
    private static int cubicchunks$maxSection(int vanilla, ChunkVertexType vertexType, WorldClient world,
            int renderDistance, CommandList commandList) {
        return CeleritasCompat.cubic(world) ? CeleritasCompat.maxSection(world) : vanilla;
    }

    // The vanilla check indexes the column's 16-slot array; outside [0, 16) it calls every section empty.
    @Inject(method = "isSectionVisuallyEmpty", at = @At("HEAD"), cancellable = true, remap = false)
    private void cubicchunks$cubeEmpty(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (CeleritasCompat.cubic(world)) {
            ExtendedBlockStorage section = CeleritasCompat.section(world, x, y, z);
            cir.setReturnValue(section == null || section.isEmpty());
        }
    }
}
