package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The accessor is inherited by DH's remote provider; its field is declared on the base. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2",remap=false)
public interface IDistantHorizonsProvider {
    @Accessor(value="levelWrapper",remap=false)
    ILevelWrapper cubicchunks$getLevelWrapper();
}
