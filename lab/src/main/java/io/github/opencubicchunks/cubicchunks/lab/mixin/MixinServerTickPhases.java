package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.core.server.TickDiagnostics;
import java.util.concurrent.FutureTask;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Measurement only: charges vanilla's queued tasks and network tick to NETWORK and Forge's tick
 * events to TICK_EVENTS. These call sites are what other mods may overwrite or move, so the mod
 * itself never wraps them; outside the lab their time stays in OTHER.
 */
@Mixin(value = MinecraftServer.class, priority = 1100)
public abstract class MixinServerTickPhases {
    @WrapOperation(method = "tick", at = {
            @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/FMLCommonHandler;onPreServerTick()V", remap = false),
            @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/FMLCommonHandler;onPostServerTick()V", remap = false)})
    private void cubiclab$serverTickEvent(FMLCommonHandler handler, Operation<Void> original) {
        TickDiagnostics.enter(TickDiagnostics.Phase.TICK_EVENTS);
        try {
            original.call(handler);
        } finally {
            TickDiagnostics.exit();
        }
    }

    @WrapOperation(method = "updateTimeLightAndEntities", at = {
            @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/FMLCommonHandler;onPreWorldTick(Lnet/minecraft/world/World;)V", remap = false),
            @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/common/FMLCommonHandler;onPostWorldTick(Lnet/minecraft/world/World;)V", remap = false)})
    private void cubiclab$worldTickEvent(FMLCommonHandler handler, World world, Operation<Void> original) {
        TickDiagnostics.enter(TickDiagnostics.Phase.TICK_EVENTS);
        try {
            original.call(handler, world);
        } finally {
            TickDiagnostics.exit();
        }
    }

    @WrapOperation(method = "updateTimeLightAndEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;runTask(Ljava/util/concurrent/FutureTask;Lorg/apache/logging/log4j/Logger;)Ljava/lang/Object;"))
    private Object cubiclab$queuedTask(FutureTask<?> task, Logger logger, Operation<Object> original) {
        TickDiagnostics.enter(TickDiagnostics.Phase.NETWORK);
        try {
            return original.call(task, logger);
        } finally {
            TickDiagnostics.exit();
        }
    }

    @WrapOperation(method = "updateTimeLightAndEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkSystem;networkTick()V"))
    private void cubiclab$networkTick(NetworkSystem network, Operation<Void> original) {
        TickDiagnostics.enter(TickDiagnostics.Phase.NETWORK);
        try {
            original.call(network);
        } finally {
            TickDiagnostics.exit();
        }
    }
}
