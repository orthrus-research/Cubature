/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Give DH's section frustum the level's real vertical span. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.RenderBufferHandler", remap = false)
public abstract class MixinDistantHorizonsCullingBounds {
    // buildRenderList passes (min, min + getMaxHeight()) as the culling box Y range,
    // while every other DH caller treats getMaxHeight() as the top block Y. Vanilla
    // levels start at 0, so both readings agree; a cubic level at [-512, 384) got a
    // box of [-512, -128) and culled visible sections whenever that box left view.
    @Redirect(method = "buildRenderList", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;getMaxHeight()I"),
            require = 1, allow = 1, remap = false)
    private int cubicchunks$frustumHeight(IClientLevelWrapper level) {
        return level.getMaxHeight() - level.getMinHeight();
    }
}
