package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Independently authored read-only observer of the client's DH data requests to the server. */
@Mixin(targets="com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue",remap=false)
public abstract class MixinLodNetworkQueue {
    /** b: 1 world generation queue, 2 sync-on-load queue. */
    @Inject(method="submitRequest",at=@At("HEAD"))
    private void cubiclab$queued(long pos,Long timestamp,CallbackInfoReturnable<?> cir){LabLodTrace.event("net-queued",pos,0,cubiclab$kind(),0,0);}
    /** a: 0 data, 1 empty (already current), 2 failure or server refusal. */
    @Inject(method="handleNetResponse",at=@At("HEAD"))
    private void cubiclab$response(@Coerce Object task,@Coerce Object response,Throwable failure,CallbackInfo ci){
        int result=failure!=null||response==null?2:LabLodTrace.field(response,"payload")==null?1:0;
        LabLodTrace.event("net-response",LabLodTrace.longField(task,"pos"),result,cubiclab$kind(),0,0);
    }
    @Unique private int cubiclab$kind(){return getClass().getSimpleName().startsWith("SyncOnLoad")?2:1;}
}
