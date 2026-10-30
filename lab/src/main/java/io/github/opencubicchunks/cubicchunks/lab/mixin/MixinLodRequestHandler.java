package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Independently authored read-only observer of client requests as the DH server receives them. */
@Mixin(targets="com.seibel.distanthorizons.core.multiplayer.server.FullDataSourceRequestHandler",remap=false)
public abstract class MixinLodRequestHandler {
    /** a: 1 generation request, 2 sync-on-load request. */
    @Inject(method="queueWorldGenForRequestMessage",at=@At("HEAD"))
    private void cubiclab$generation(@Coerce Object player,@Coerce Object message,@Coerce Object limits,CallbackInfo ci){
        LabLodTrace.event("srv-request",LabLodTrace.longField(message,"sectionPos"),1);
    }
    @Inject(method="queueLodSyncForRequestMessage",at=@At("HEAD"))
    private void cubiclab$sync(@Coerce Object player,@Coerce Object message,@Coerce Object limits,CallbackInfo ci){
        LabLodTrace.event("srv-request",LabLodTrace.longField(message,"sectionPos"),2);
    }
    @Inject(method="onWorldGenTaskComplete",at=@At("HEAD"))
    private void cubiclab$generated(long pos,CallbackInfo ci){LabLodTrace.event("srv-generated",pos);}
}
