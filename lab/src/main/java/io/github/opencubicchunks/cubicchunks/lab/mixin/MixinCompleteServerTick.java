package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.CubicLab;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

/** Always-on bounded timer; expensive storage accounting is still opt-in. */
@Mixin(MinecraftServer.class)
public abstract class MixinCompleteServerTick {
    @WrapMethod(method="tick")
    private void tick(Operation<Void> original){
        Object observer=CubicLab.serverTickObserver();long start=System.nanoTime();boolean ok=false;
        LabStorageTrace.Token token=LabStorageTrace.begin("completeServerTick",this);
        try{original.call();ok=true;}finally{
            long duration=System.nanoTime()-start;LabStorageTrace.end(token,ok,0);
            CubicLab.serverTickCompleted(observer,duration,ok);
        }
    }
}
