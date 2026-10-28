/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.CubicDistantMaterials;
import net.minecraft.block.state.IBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gives blocks DH files as UNKNOWN the shader material their Minecraft material implies. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper", remap = false)
public abstract class MixinDistantHorizonsMaterials {
    @Inject(method = "calculateEDhApiBlockMaterialId", at = @At("RETURN"), cancellable = true,
            require = 1, remap = false)
    private static void cubicchunks$materialFallback(IBlockState state, String name, boolean liquid,
            CallbackInfoReturnable<EDhApiBlockMaterial> result) {
        if (result.getReturnValue() != EDhApiBlockMaterial.UNKNOWN) return;
        String material = CubicDistantMaterials.name(state);
        if (material != null) result.setReturnValue(EDhApiBlockMaterial.valueOf(material));
    }
}
