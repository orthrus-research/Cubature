package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.CubicChunkSnapshot;
import java.util.ArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Received snapshots already carry CC's authoritative block light. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.generation.DhLightingEngine",remap=false)
public abstract class MixinDistantHorizonsLighting {
    @Inject(method="bakeChunkBlockLighting",at=@At("HEAD"),cancellable=true,remap=false)
    private void nativeCubicLight(IChunkWrapper center,ArrayList<IChunkWrapper> neighbors,int sky,CallbackInfo callback) {
        if(center instanceof CubicChunkSnapshot)callback.cancel();
    }
}
