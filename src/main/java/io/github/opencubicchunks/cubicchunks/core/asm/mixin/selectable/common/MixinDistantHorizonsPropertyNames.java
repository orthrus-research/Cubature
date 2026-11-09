/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantPropertyNames;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serializes a block state's property values by their names where their class inherits Object's {@code toString}, so
 * modded values without one (GregTech's ore {@code stone_type}) match across JVMs and starts; every other value keeps
 * its {@code toString}. DH deserializes by comparing a stored string with this method's output for each of the
 * block's states, so one hook serves both sides.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper", remap = false)
public abstract class MixinDistantHorizonsPropertyNames {
    @Inject(method = "serializeBlockStateProperties", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void cubicchunks$propertyNames(IBlockState state, CallbackInfoReturnable<String> result) {
        result.setReturnValue(DistantPropertyNames.serialize(state));
    }
}
