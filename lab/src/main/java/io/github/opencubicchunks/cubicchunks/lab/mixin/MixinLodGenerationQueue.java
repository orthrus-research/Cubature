package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Independently authored read-only observer of DH's server world-generation queue: waiting, started, finished. */
@Mixin(targets="com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue",remap=false)
public abstract class MixinLodGenerationQueue {
    /** a: the data detail level requested. */
    @Inject(method="submitRetrievalTask",at=@At("HEAD"))
    private void cubiclab$submitted(long pos,byte detail,CallbackInfoReturnable<?> cir){LabLodTrace.event("gen-submitted",pos,detail);}
    @Inject(method="startWorldGenTaskGroup",at=@At("HEAD"))
    private void cubiclab$started(@Coerce Object task,CallbackInfo ci){
        LabLodTrace.event("gen-started",LabLodTrace.longField(task,"pos"),LabLodTrace.longField(task,"requestDetailLevel"));
    }
    /** a: 0 generated, -1 failed or cancelled. */
    @Inject(method="lambda$startWorldGenTaskGroup$1",at=@At("HEAD"))
    private void cubiclab$finished(long pos,@Coerce Object task,@Coerce Object data,Throwable failure,CallbackInfoReturnable<?> cir){
        LabLodTrace.event("gen-finished",pos,failure==null?0:-1);
    }
}
