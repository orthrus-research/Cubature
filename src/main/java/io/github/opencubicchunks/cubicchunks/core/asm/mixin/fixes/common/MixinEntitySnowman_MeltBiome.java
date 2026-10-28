/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.fixes.common;

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla melts a snow golem by its column's biome at Y 0. A cubic world can report its own biome
 * per cube below the surface, so the golem melts by the biome where it stands.
 */
@Mixin(EntitySnowman.class)
public class MixinEntitySnowman_MeltBiome {

    @Redirect(method = "onLivingUpdate", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/World;getBiome(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome;"))
    private Biome standingBiome(World world, BlockPos pos) {
        if (!((ICubicWorld) world).isCubicWorld()) {
            return world.getBiome(pos);
        }
        Entity self = (Entity) (Object) this;
        return world.getBiome(new BlockPos(self.posX, self.posY, self.posZ));
    }
}
