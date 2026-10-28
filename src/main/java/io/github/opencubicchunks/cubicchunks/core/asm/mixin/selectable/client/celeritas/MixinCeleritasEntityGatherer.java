/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.celeritas;

import java.util.List;
import java.util.function.Consumer;

import io.github.opencubicchunks.cubicchunks.core.compat.celeritas.CeleritasCompat;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gather the entities Celeritas draws from the world's list; a cubic column holds none of its own. */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.entity.EntityGatherer")
public abstract class MixinCeleritasEntityGatherer {
    @Shadow(remap = false) @Final private List<Entity>[] entityLists;
    @Shadow(remap = false) @Final private Consumer<Entity> addEntity;

    // Celeritas reads each loaded column's 16 entity lists, which are empty in a cubic world because entities live
    // in cubes, so mobs vanished from the main pass while Iris's shadow pass still drew them. The world's list is
    // what Celeritas itself falls back to, and the client drops a cube's entities from it when the cube unloads.
    @Inject(method = "getLoadedEntityList", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void cubicchunks$worldEntities(WorldClient world, CallbackInfoReturnable<List<Entity>[]> cir) {
        if (CeleritasCompat.cubic(world)) {
            world.loadedEntityList.forEach(addEntity);
            cir.setReturnValue(entityLists);
        }
    }
}
