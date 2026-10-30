package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Independently authored read-only observer: the server encodes one payload per section it sends. */
@Mixin(targets="com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayload",remap=false)
public abstract class MixinLodPayload {
    @Shadow public int dtoBufferId;
    /** a: encoded bytes, b: the payload's buffer id, which srv-response repeats. */
    @Inject(method="<init>(Lcom/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2;Ljava/util/List;)V",at=@At("RETURN"))
    private void cubiclab$encoded(@Coerce Object data,List<?> beams,CallbackInfo ci){
        Object buffer=LabLodTrace.field(this,"dtoBuffer");Object bytes=LabLodTrace.call(buffer,"readableBytes");
        LabLodTrace.event("srv-payload",((Number)LabLodTrace.call(data,"getPos")).longValue(),
            bytes instanceof Number?((Number)bytes).longValue():-1,dtoBufferId,0,0);
    }
}
