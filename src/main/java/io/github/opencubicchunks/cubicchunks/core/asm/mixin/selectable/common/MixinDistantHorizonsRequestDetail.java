package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** DH forces INTERNAL_SERVER on 1.12.2, but a seed-only API provider has its own detail limits. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.multiplayer.server.FullDataSourceRequestHandler",remap=false)
public abstract class MixinDistantHorizonsRequestDetail {
    @Shadow(remap=false) @Final private AbstractDhServerLevel serverLevel;
    @WrapOperation(method="lambda$tryFulfillDataSourceRequestGroup$0",at=@At(value="INVOKE",
            target="Lcom/seibel/distanthorizons/core/config/types/ConfigEntry;get()Ljava/lang/Object;"),remap=false)
    private Object cubicDetailLimit(ConfigEntry<?> entry, Operation<Object> original) {
        Object mode=original.call(entry);
        Object raw=serverLevel.getLevelWrapper().getWrappedMcObject();
        if (mode==EDhApiDistantGeneratorMode.INTERNAL_SERVER && raw instanceof World
                && DistantHorizonsCompat.hasTerrainPreview((World)raw)) return EDhApiDistantGeneratorMode.SURFACE;
        return mode;
    }
}
