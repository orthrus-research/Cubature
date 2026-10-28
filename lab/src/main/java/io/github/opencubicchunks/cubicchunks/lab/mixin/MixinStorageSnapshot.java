package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="io.github.opencubicchunks.cubicchunks.core.server.chunkio.IONbtWriter",remap=false)
public abstract class MixinStorageSnapshot {
    @WrapMethod(method="write(Lio/github/opencubicchunks/cubicchunks/core/world/cube/Cube;)Lnet/minecraft/nbt/NBTTagCompound;")
    private static NBTTagCompound cube(Cube cube,Operation<NBTTagCompound> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("cubeSnapshot",cube.getWorld());boolean ok=false;
        try{NBTTagCompound result=original.call(cube);ok=true;return result;}finally{LabStorageTrace.end(t,ok,0);}
    }
    @WrapMethod(method="write(Lnet/minecraft/world/chunk/Chunk;)Lnet/minecraft/nbt/NBTTagCompound;")
    private static NBTTagCompound column(Chunk column,Operation<NBTTagCompound> original){
        LabStorageTrace.Token t=LabStorageTrace.begin("columnSnapshot",column.getWorld());boolean ok=false;
        try{NBTTagCompound result=original.call(column);ok=true;return result;}finally{LabStorageTrace.end(t,ok,0);}
    }
}
