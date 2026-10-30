package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Independently authored read-only observer: tells the LOD trace when DH has built or torn down its
 * shared executors, so the pool sampler reads them only while they exist and never initializes DH.
 */
@Mixin(targets="com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil",remap=false)
public abstract class MixinLodThreadPools {
    @Inject(method="setupThreadPools",at=@At("RETURN"))
    private static void cubiclab$ready(CallbackInfo ci){LabLodTrace.poolsReady(true);}
    @Inject(method="shutdownThreadPools",at=@At("HEAD"))
    private static void cubiclab$gone(CallbackInfo ci){LabLodTrace.poolsReady(false);}
}
