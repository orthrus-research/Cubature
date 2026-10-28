package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.core.server.ChunkGc;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=ChunkGc.class,remap=false)
public abstract class MixinStorageCubePurge {
    @WrapMethod(method="chunkGc")
    private void purge(Operation<Void> original) {
        LabStorageTrace.Token token=LabStorageTrace.begin("cubePurgePass",this);boolean success=false;
        try{original.call();success=true;}finally{LabStorageTrace.end(token,success,0);}
    }
}
