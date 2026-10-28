package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.RegionCubeStorage;
import io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage.NBTBatch;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=RegionCubeStorage.class,remap=false)
public abstract class MixinStorageBackend {
    @WrapMethod(method="compressNBT")
    private ByteBuf compress(NBTTagCompound nbt,Operation<ByteBuf> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("compressNbt",this);boolean ok=false;long bytes=0;
        try{ByteBuf result=original.call(nbt);bytes=result.readableBytes();LabStorageTrace.compressed(bytes);ok=true;return result;}finally{LabStorageTrace.end(t,ok,bytes);}
    }
    @WrapMethod(method="writeBatch")
    private void batch(NBTBatch batch,Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("backendBatch",this);boolean ok=false;
        LabStorageTrace.Batch accounting=LabStorageTrace.beginBatch(batch);
        try{original.call(batch);ok=true;}finally{LabStorageTrace.endBatch(accounting);LabStorageTrace.end(t,ok,0);}
    }
    @WrapMethod(method="flush")
    private void flush(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("backendFlush",this);boolean ok=false;
        try{original.call();ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
    @WrapMethod(method="close")
    private void close(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("backendClose",this);boolean ok=false;
        try{original.call();ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
}
