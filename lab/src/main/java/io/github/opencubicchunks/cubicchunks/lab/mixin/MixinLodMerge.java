package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodMerge;
import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Independently authored read-only observer of DH's full-data merge: before a section takes in another, counts the
 * columns the merge is about to replace or stamp (LabLodMerge). Changes no data and no result.
 */
@Mixin(targets="com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2",remap=false)
public abstract class MixinLodMerge {
    @Shadow @Final private long pos;
    @Inject(method="updateFromDataSource",at=@At("HEAD"))
    private void cubiclab$merge(@Coerce Object input,CallbackInfoReturnable<Boolean> cir){
        if(LabLodTrace.ENABLED)LabLodMerge.observe(this,pos,input);
    }
    /** After DH's same-detail merge, before it drops unused ids, so unchanged columns still compare equal. */
    @Inject(method="updateFromSameDetailLevel",at=@At("RETURN"))
    private void cubiclab$mergedSame(@Coerce Object input,int[] remappedIds,CallbackInfoReturnable<Boolean> cir){
        if(LabLodTrace.ENABLED)LabLodMerge.afterSame(this,pos);
    }
}
