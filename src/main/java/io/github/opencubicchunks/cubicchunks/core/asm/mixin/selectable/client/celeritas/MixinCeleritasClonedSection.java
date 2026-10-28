/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.celeritas;

import java.util.Map;

import io.github.opencubicchunks.cubicchunks.core.compat.celeritas.CeleritasCompat;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clone a section's blocks and tile entities from its cube rather than from the column. */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.world.cloned.ClonedChunkSection")
public abstract class MixinCeleritasClonedSection {
    @Inject(method = "getChunkSection", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cubicchunks$cubeSection(Chunk column, int y, CallbackInfoReturnable<ExtendedBlockStorage> cir) {
        World world = column.getWorld();
        if (CeleritasCompat.cubic(world)) {
            cir.setReturnValue(CeleritasCompat.section(world, column.x, y, column.z));
        }
    }

    // The constructor asks the column for its tile entities twice: an emptiness check, then the section filter.
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/Chunk;getTileEntityMap()Ljava/util/Map;"), require = 2, allow = 2)
    private Map<BlockPos, TileEntity> cubicchunks$cubeTileEntities(Chunk column, World world, int x, int y, int z) {
        return CeleritasCompat.cubic(world) ? CeleritasCompat.tileEntities(world, x, y, z) : column.getTileEntityMap();
    }
}
