package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cubicchunks.regionlib.api.region.key.RegionKey;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.ShadowPagingRegion;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageFaults;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value=ShadowPagingRegion.Builder.class,remap=false)
public abstract class MixinStorageRegionBuilder {
    @Shadow private Path directory;
    @Shadow private RegionKey regionKey;
    @WrapMethod(method="build")
    private ShadowPagingRegion<?> build(Operation<ShadowPagingRegion<?>> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("regionOpen",this);boolean ok=false;
        try{ShadowPagingRegion<?> region=original.call();LabStorageFaults.register(region,directory.resolve(regionKey.getName()));ok=true;return region;}
        finally{LabStorageTrace.end(t,ok,0);}
    }
}
