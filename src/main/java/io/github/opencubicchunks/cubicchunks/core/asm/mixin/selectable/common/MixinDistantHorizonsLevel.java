package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional DH level datum adapter; vanilla levels retain their original methods. */
@Pseudo
@Mixin(targets={"com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper",
        "com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper"},remap=false)
public abstract class MixinDistantHorizonsLevel {
    @Inject(method="getMinHeight",at=@At("HEAD"),cancellable=true,remap=false)
    private void cubicMin(CallbackInfoReturnable<Integer> result) {
        ICubicWorld world=(ICubicWorld)((IDhApiLevelWrapper)(Object)this).getWrappedMcObject();
        if(world.isCubicWorld())result.setReturnValue(world.getMinHeight());
    }
    @Inject(method="getMaxHeight",at=@At("HEAD"),cancellable=true,remap=false)
    private void cubicMax(CallbackInfoReturnable<Integer> result) {
        ICubicWorld world=(ICubicWorld)((IDhApiLevelWrapper)(Object)this).getWrappedMcObject();
        if(world.isCubicWorld())result.setReturnValue(world.getMaxHeight());
    }
}
