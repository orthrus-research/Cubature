/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.common;

import io.github.opencubicchunks.cubicchunks.core.server.PlayerCubeMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * A teleport into a column that is neither loaded nor saved makes the server thread generate that
 * column at once (Entity.setPositionAndRotation reads its chunk), stalling every player while the
 * generator works. In cubic worlds with generation workers, PlayerCubeMap.arrivals() holds the
 * teleport until the workers have prepared the column, and completes it then.
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServer_DeferredArrival {

    @Shadow public EntityPlayerMP player;

    @Inject(method = "setPlayerLocation(DDDFFLjava/util/Set;)V", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$deferArrival(double x, double y, double z, float yaw, float pitch,
            Set<SPacketPlayerPosLook.EnumFlags> relative, CallbackInfo ci) {
        if (!(player.world instanceof WorldServer)) return;
        PlayerChunkMap map = ((WorldServer) player.world).getPlayerChunkMap();
        if (map instanceof PlayerCubeMap && ((PlayerCubeMap) map).arrivals().defer(player, x, y, z, yaw, pitch, relative)) {
            ci.cancel();
        }
    }
}
