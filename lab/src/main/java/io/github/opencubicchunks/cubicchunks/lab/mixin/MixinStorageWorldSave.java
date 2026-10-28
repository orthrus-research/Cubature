package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import net.minecraft.world.WorldServer;
import net.minecraft.util.IProgressUpdate;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldServer.class)
public abstract class MixinStorageWorldSave {
    @WrapMethod(method="saveAllChunks")
    private void all(boolean save,IProgressUpdate progress,Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("worldSaveAllChunks",this);boolean ok=false;
        try{original.call(save,progress);ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
    @WrapMethod(method="saveLevel")
    private void metadata(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("worldMetadataSave",this);boolean ok=false;
        try{original.call();ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
}
