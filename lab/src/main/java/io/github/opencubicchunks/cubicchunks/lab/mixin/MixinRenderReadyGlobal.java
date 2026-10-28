package io.github.opencubicchunks.cubicchunks.lab.mixin;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderReadyAccess;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderReadyHooks;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.Set;
@Mixin(RenderGlobal.class)
public abstract class MixinRenderReadyGlobal implements LabRenderReadyAccess {
    @Shadow private List<?> renderInfos;
    @Shadow private Set<BlockPos> setLightUpdates;
    @Shadow private WorldClient world;
    @Unique private RenderChunk[] cubiclab$lastChunks=new RenderChunk[0];
    @Unique private long[] cubiclab$lastPositions=new long[0];
    @Unique private int cubiclab$demandGeneration;
    @Inject(method="setWorldAndLoadRenderers",at=@At("HEAD"))private void cubiclab$world(WorldClient world,CallbackInfo ci){LabRenderReadyHooks.world(world);}
    @Inject(method="notifyLightSet",at=@At("RETURN"))private void cubiclab$light(BlockPos position,CallbackInfo ci){LabRenderReadyHooks.dataChanged(world);}
    public void cubiclab$visitOutstanding(java.util.function.Consumer<int[]> visitor){
        for(Object info:renderInfos){
            RenderChunk chunk=((RenderReadyInfoAccess)info).cubiclab$chunk();
            boolean uncompiled=chunk.getCompiledChunk()==CompiledChunk.DUMMY,skipped=LabRenderReadyHooks.skipped(chunk),dirty=chunk.needsUpdate();
            if(uncompiled||skipped||dirty){BlockPos p=chunk.getPosition();visitor.accept(new int[]{p.getX()>>4,p.getY()>>4,p.getZ()>>4,uncompiled?1:0,skipped?1:0,dirty?1:0});}
        }
    }
    public int[] cubiclab$renderDemand(){
        int dirty=0,uncompiled=0,skipped=0,index=0;boolean changed=false;
        if(renderInfos.size()>32768)throw new IllegalStateException("Renderer demand exceeds readiness capacity");
        if(cubiclab$lastChunks.length!=renderInfos.size()){
            cubiclab$lastChunks=new RenderChunk[renderInfos.size()];cubiclab$lastPositions=new long[renderInfos.size()];changed=true;
        }
        for(Object info:renderInfos){
            RenderChunk chunk=((RenderReadyInfoAccess)info).cubiclab$chunk();
            if(chunk.needsUpdate())dirty++;if(chunk.getCompiledChunk()==CompiledChunk.DUMMY)uncompiled++;
            if(LabRenderReadyHooks.skipped(chunk))skipped++;
            long position=chunk.getPosition().toLong();
            if(cubiclab$lastChunks[index]!=chunk||cubiclab$lastPositions[index]!=position)changed=true;
            cubiclab$lastChunks[index]=chunk;cubiclab$lastPositions[index++]=position;
        }
        if(changed)cubiclab$demandGeneration++;
        return new int[]{renderInfos.size(),dirty,uncompiled,skipped,setLightUpdates.size(),cubiclab$demandGeneration};
    }
}
