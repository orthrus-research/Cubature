package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import java.util.Map;

@Mixin(targets="io.github.opencubicchunks.cubicchunks.core.server.chunkio.PendingCubeWrites",remap=false)
public abstract class MixinStorageWriter {
    @Shadow @Final Map<ChunkPos,NBTTagCompound> columns;
    @Shadow @Final Map<CubePos,NBTTagCompound> cubes;
    @WrapMethod(method="putCube")
    private void cube(CubePos pos,NBTTagCompound nbt,Operation<Void> original){synchronized(this){original.call(pos,nbt);LabStorageTrace.published(this,nbt,columns,cubes);}}
    @WrapMethod(method="putColumn")
    private void column(ChunkPos pos,NBTTagCompound nbt,Operation<Void> original){synchronized(this){original.call(pos,nbt);LabStorageTrace.published(this,nbt,columns,cubes);}}
    @WrapMethod(method="writeBatch")
    private void batch(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("writerBatch",this);boolean ok=false;
        try{original.call();ok=true;}finally{synchronized(this){LabStorageTrace.observeQueue(this,columns,cubes);}LabStorageTrace.end(t,ok,0);}
    }
    @WrapMethod(method="flush")
    private void flush(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("writerFlush",this);boolean ok=false;
        try{original.call();ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
    @WrapMethod(method="lockInterruptibly")
    private void waitWriter(Operation<Void> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("writerLockWait",this);boolean ok=false;
        try{original.call();ok=true;}finally{LabStorageTrace.end(t,ok,0);}
    }
}
