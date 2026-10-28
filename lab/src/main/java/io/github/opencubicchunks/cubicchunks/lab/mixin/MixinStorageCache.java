package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cubicchunks.regionlib.lib.provider.SharedCachedRegionProvider;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=SharedCachedRegionProvider.class,remap=false)
public abstract class MixinStorageCache {
    @WrapMethod(method="flushRegions")
    private static void flush(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("globalCacheFlush",SharedCachedRegionProvider.class);boolean ok=false;
        try{original.call();ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
    @WrapMethod(method="clearRegions")
    private static void close(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("globalCacheClear",SharedCachedRegionProvider.class);boolean ok=false;
        try{original.call();ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
}
