package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabDeliveryTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Independently authored read-only observer for the pinned upstream section API. */
@Mixin(targets="com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection",remap=false)
public abstract class MixinDeliveryDhSection {
    @Shadow public long pos;
    @Shadow public abstract boolean canRender();
    @Shadow public abstract boolean gpuUploadComplete();
    @Unique private long cubiclab$epoch;
    @Unique private int cubiclab$state=-1;
    @Inject(method="setRenderingEnabled",at=@At("RETURN"))
    private void cubiclab$selected(boolean enabled,CallbackInfo ci){
        int state=(enabled?1:0)|(canRender()?2:0)|(gpuUploadComplete()?4:0);
        long epoch=LabDeliveryTrace.epoch();
        if(epoch!=cubiclab$epoch||state!=cubiclab$state){
            cubiclab$epoch=epoch;cubiclab$state=state;
            LabDeliveryTrace.event("dh-selection",0,0,0,pos,state);
        }
    }
    @Inject(method="close",at=@At("HEAD"))
    private void cubiclab$closed(CallbackInfo ci){LabDeliveryTrace.event("dh-close",0,0,0,pos,0);}
}
