package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.*;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import java.util.*;

@Mixin(MinecraftServer.class)
public abstract class MixinStorageServer {
    @WrapMethod(method="stopServer")
    private void stop(Operation<Void> original){
        LabStorageTrace.start();long start=System.nanoTime();boolean ok=false;
        try{original.call();ok=true;}finally{
            Map<String,Object> out=new LinkedHashMap<>();out.put("normalReturn",ok);out.put("elapsedNanos",System.nanoTime()-start);
            out.put("runtime",LabData.runtime());out.put("storageDiagnostics",LabStorageTrace.finish());
            out.put("scope","MinecraftServer.stopServer bracket. Includes actual player/world save and close calls inside this method; later FML lifecycle work is outside this boundary.");
            LabData.write(((MinecraftServer)(Object)this).getFile("cubiclab-lifecycle").toPath().resolve("shutdown-"+UUID.randomUUID()+".json"),out);
        }
    }
}
