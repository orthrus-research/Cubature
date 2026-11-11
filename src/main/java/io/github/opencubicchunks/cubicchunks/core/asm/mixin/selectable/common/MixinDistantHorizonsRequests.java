package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.level.IDhLevel;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import org.spongepowered.asm.mixin.injection.At;

/** Keep DH's normal validation/rate limits; admit coarse requests for a real provider. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.level.AbstractDhServerLevel",remap=false)
public abstract class MixinDistantHorizonsRequests {
    @Unique private boolean cubicchunks$requestLogged;
    @WrapOperation(method="lambda$registerNetworkHandlers$0",at=@At(value="FIELD",
            target="Lcom/seibel/distanthorizons/api/enums/worldGeneration/EDhApiGeneratorPlan;surfaceGenEnabled:Z"),remap=false)
    private boolean cubicSurfaceRequests(EDhApiGeneratorPlan plan, Operation<Boolean> original) {
        Object raw=((IDhLevel)(Object)this).getLevelWrapper().getWrappedMcObject();
        boolean supported=original.call(plan) || (plan.generationEnabled && raw instanceof World
                && DistantHorizonsCompat.hasTerrainPreview((World)raw));
        if (!cubicchunks$requestLogged) {
            CubicChunks.LOGGER.info("CC/DH coarse request capability={} world={}",supported,raw.getClass().getName());
            cubicchunks$requestLogged=true;
        }
        return supported;
    }
}
