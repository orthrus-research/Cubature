package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Per-level capability, without enabling vanilla rough generation on Minecraft 1.12.2. */
@Pseudo
@Mixin(targets={"com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2",
        "com.seibel.distanthorizons.core.file.fullDatafile.RemoteFullDataSourceProvider"},remap=false)
public abstract class MixinDistantHorizonsPlan {
    @Unique private Boolean cubicchunks$previewSupported;
    @Inject(method="getGeneratorPlan",at=@At("RETURN"),cancellable=true,remap=false)
    private void cubicPlan(CallbackInfoReturnable<EDhApiGeneratorPlan> result) {
        Object raw=((IDistantHorizonsProvider)(Object)this).cubicchunks$getLevelWrapper().getWrappedMcObject();
        boolean supported=raw instanceof World && DistantHorizonsCompat.hasTerrainPreview((World)raw);
        if (cubicchunks$previewSupported==null || cubicchunks$previewSupported!=supported) {
            CubicChunks.LOGGER.info("CC/DH level plan: wrapper={} provider={} configured={}",raw==null?"null":raw.getClass().getName(),supported,result.getReturnValue());
            cubicchunks$previewSupported=supported;
        }
        if (result.getReturnValue().generationEnabled && supported) result.setReturnValue(EDhApiGeneratorPlan.SURFACE_ONLY);
    }
}
