package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Independently authored read-only observer: the client builds this message only to send a request. */
@Mixin(targets="com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage",remap=false)
public abstract class MixinLodRequestMessage {
    @Shadow public long sectionPos;
    @Inject(method="<init>(Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/ILevelWrapper;JLjava/lang/Long;)V",at=@At("RETURN"))
    private void cubiclab$sent(CallbackInfo ci){LabLodTrace.event("net-sent",sectionPos);}
}
