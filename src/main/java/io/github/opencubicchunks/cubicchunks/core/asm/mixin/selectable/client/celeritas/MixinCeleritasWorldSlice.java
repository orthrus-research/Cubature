/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.celeritas;

import io.github.opencubicchunks.cubicchunks.core.compat.celeritas.CeleritasCompat;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.embeddedt.embeddium.impl.util.position.SectionPos;
import org.taumc.celeritas.impl.world.cloned.ClonedChunkSectionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * prepare() skips empty sections by reading {@code column.getBlockStorageArray()[pos.y()]}, which fails outside
 * [0, 16) in a cubic world. Read a slot that always exists, then replace what was read with the cube's blocks.
 */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.world.WorldSlice")
public abstract class MixinCeleritasWorldSlice {
    @Redirect(method = "prepare", at = @At(value = "INVOKE",
            target = "Lorg/embeddedt/embeddium/impl/util/position/SectionPos;y()I", ordinal = 0, remap = false),
            require = 1, allow = 1, remap = false)
    private static int cubicchunks$safeSlot(SectionPos pos, World world, SectionPos section, ClonedChunkSectionCache cache) {
        return CeleritasCompat.cubic(world) ? 0 : pos.y();
    }

    @ModifyVariable(method = "prepare", at = @At(value = "STORE", ordinal = 0), ordinal = 0, require = 1, allow = 1,
            remap = false)
    private static ExtendedBlockStorage cubicchunks$cubeSection(ExtendedBlockStorage read, World world, SectionPos pos,
            ClonedChunkSectionCache cache) {
        return CeleritasCompat.cubic(world) ? CeleritasCompat.section(world, pos.x(), pos.y(), pos.z()) : read;
    }
}
