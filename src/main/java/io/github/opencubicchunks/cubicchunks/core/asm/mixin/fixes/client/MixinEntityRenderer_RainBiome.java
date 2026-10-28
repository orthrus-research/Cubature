/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.fixes.client;

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla decides whether a column shows rain or snow from its biome at Y 0. A cubic world can
 * report its own biome per cube below the surface, so the column's weather comes from the biome
 * where the precipitation lands instead.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer_RainBiome {

    @Redirect(method = "renderRainSnow", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBiome(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome;"))
    private Biome precipitationBiome(World world, BlockPos pos) {
        if (!((ICubicWorld) world).isCubicWorld()) {
            return world.getBiome(pos);
        }
        return world.getBiome(world.getPrecipitationHeight(pos));
    }
}
