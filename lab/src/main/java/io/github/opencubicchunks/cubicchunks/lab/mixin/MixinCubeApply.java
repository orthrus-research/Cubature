package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "io.github.opencubicchunks.cubicchunks.core.network.PacketCubes$Handler", remap = false)
public abstract class MixinCubeApply {
    @Inject(method = "handleClientMessage(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;Lio/github/opencubicchunks/cubicchunks/core/network/PacketCubes;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)V", at = @At("HEAD"), remap = false)
    private void cubiclab$begin(CallbackInfo ci) {
        LabHooks.apply.remove();
        LabHooks.PacketCounters counters = LabHooks.clientPackets;
        if (counters != null) LabHooks.apply.set(new LabHooks.Invocation(counters, 0, 0));
    }
    @Inject(method = "handleClientMessage(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;Lio/github/opencubicchunks/cubicchunks/core/network/PacketCubes;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)V", at = @At("RETURN"), remap = false)
    private void cubiclab$end(CallbackInfo ci) {
        LabHooks.Invocation invocation = LabHooks.apply.get(); LabHooks.apply.remove();
        if (invocation != null) {
            invocation.counters.recordApply(System.nanoTime() - invocation.started);
        }
    }
}
