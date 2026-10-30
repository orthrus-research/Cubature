package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Independently authored read-only observer: the server builds a response once a payload's chunks are sent. */
@Mixin(targets="com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage",remap=false)
public abstract class MixinLodResponse {
    /** b: the payload buffer id from srv-payload. */
    @Inject(method="<init>(Lcom/seibel/distanthorizons/core/multiplayer/fullData/FullDataPayload;)V",at=@At("RETURN"))
    private void cubiclab$response(@Coerce Object payload,CallbackInfo ci){
        if(payload!=null)LabLodTrace.event("srv-response",0,0,LabLodTrace.longField(payload,"dtoBufferId"),0,0);
    }
}
