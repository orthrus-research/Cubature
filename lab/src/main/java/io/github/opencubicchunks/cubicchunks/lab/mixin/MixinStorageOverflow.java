package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cubicchunks.regionlib.lib.ExtRegion;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageFaults;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import java.io.IOException;
import java.nio.file.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=ExtRegion.class,remap=false)
public abstract class MixinStorageOverflow {
    @WrapOperation(method="writeValue",at=@At(value="INVOKE",target="Ljava/nio/file/Files;move(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"))
    private Path move(Path from, Path to, CopyOption[] options, Operation<Path> original)throws IOException{
        LabStorageTrace.Token t=LabStorageTrace.begin("overflowRename",this);boolean ok=false;
        try{LabStorageFaults.beforePath(from,"overflow-rename");Path result=original.call(from,to,options);ok=true;return result;}
        finally{LabStorageTrace.end(t,ok,0);}
    }
}
