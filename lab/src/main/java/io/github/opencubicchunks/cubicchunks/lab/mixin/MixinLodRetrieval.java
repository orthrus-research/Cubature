package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Independently authored read-only observer of DH data retrieval. On the server it brackets
 * generation; on the client (RemoteFullDataSourceProvider extends this class) it brackets the
 * network request through to the received data.
 */
@Mixin(targets="com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider",remap=false)
public abstract class MixinLodRetrieval {
    @Unique private static final ThreadLocal<long[]> cubiclab$started=ThreadLocal.withInitial(()->new long[1]);
    @Inject(method="queuePositionForRetrieval",at=@At("HEAD"))
    private void cubiclab$queued(Long pos,CallbackInfoReturnable<?> cir){LabLodTrace.event("retrieval-queued",pos);}
    /** a: see {@link LabLodTrace#outcome}. */
    @Inject(method="onWorldGenTaskComplete",at=@At("HEAD"))
    private void cubiclab$done(Long pos,@Coerce Object result,Throwable failure,CallbackInfo ci){
        cubiclab$started.get()[0]=System.nanoTime();
        LabLodTrace.event("retrieval-done",pos,LabLodTrace.outcome(result,failure));
    }
    /** a: nanoseconds DH spent on this thread merging, lighting and saving the data and notifying listeners. */
    @Inject(method="onWorldGenTaskComplete",at=@At("RETURN"))
    private void cubiclab$updated(Long pos,@Coerce Object result,Throwable failure,CallbackInfo ci){
        LabLodTrace.event("retrieval-end",pos,System.nanoTime()-cubiclab$started.get()[0]);
    }
}
