package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodFallback;
import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Independently authored read-only observer of DH's client quad tree: data wanted, reloads, queue depths, and sections
 * drawn in place of children that are not all ready (LabLodFallback).
 */
@Mixin(targets="com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree",remap=false)
public abstract class MixinLodQuadTree {
    @Shadow private ArrayList<?> enabledSections;
    @Shadow @Final private Set<Long> missingGenerationPosSet;
    @Shadow @Final private Set<Long> queuedGenerationPosSet;
    @Unique private long cubiclab$lastTree;
    /** Created on first use: a Mixin field initializer did not reach DH's tree in a native run. */
    @Unique private LabLodFallback cubiclab$fallback;
    @Unique private LabLodFallback cubiclab$fallback(){
        if(cubiclab$fallback==null)cubiclab$fallback=new LabLodFallback();
        return cubiclab$fallback;
    }
    @Inject(method="updateAllRenderSections",at=@At("HEAD"))
    private void cubiclab$pass(CallbackInfo ci){if(LabLodTrace.ENABLED)cubiclab$fallback().begin();}
    /** DH's fallback branch: the parent renders and its children are disabled. */
    @Inject(method="onDetailLevelTooLow",at=@At(value="INVOKE",
        target="Lcom/seibel/distanthorizons/core/render/QuadTree/LodQuadTree;recursivelyDisableChildNodes(Lcom/seibel/distanthorizons/core/util/objects/quadTree/QuadNode;)V"))
    private void cubiclab$fellBack(@Coerce Object playerPos,@Coerce Object rootNode,@Coerce Object quadNode,CallbackInfoReturnable<Boolean> cir){
        if(!LabLodTrace.ENABLED)return;
        int renderable=0,present=0;
        for(String child:CHILDREN){
            Object node=LabLodTrace.field(quadNode,child),section=node==null?null:LabLodTrace.field(node,"value");
            if(section==null)continue;
            present++;
            if(Boolean.TRUE.equals(LabLodTrace.call(section,"canRender")))renderable++;
        }
        cubiclab$fallback().fallback(LabLodTrace.longField(quadNode,"sectionPos"),renderable,present);
    }
    @Unique private static final String[] CHILDREN={"nwChild","neChild","swChild","seChild"};
    @Inject(method="tryQueuePosForRetrieval",at=@At("HEAD"))
    private void cubiclab$wanted(long pos,CallbackInfo ci){LabLodTrace.event("retrieval-wanted",pos);}
    @Inject(method="queuePosToReload",at=@At("HEAD"))
    private void cubiclab$reload(long pos,CallbackInfo ci){LabLodTrace.event("reload-queued",pos);}
    /**
     * At most 4 Hz: a enabled sections, b missing and c queued retrieval positions, d sections waiting to load; and a
     * fallback row: a parents drawn in place of their children, b of them hiding a child that could render, c such
     * children.
     */
    @Inject(method="updateAllRenderSections",at=@At("RETURN"))
    private void cubiclab$tree(CallbackInfo ci){
        if(!LabLodTrace.ENABLED)return;
        cubiclab$fallback().end();
        long now=System.nanoTime();
        if(now-cubiclab$lastTree<250_000_000L)return;
        cubiclab$lastTree=now;
        Object loads=LabLodTrace.call(LabLodTrace.field(this,"tickNodeHolder"),"getLoadSections");
        LabLodTrace.event("tree",0,enabledSections.size(),missingGenerationPosSet.size(),queuedGenerationPosSet.size(),
            loads instanceof Collection?((Collection<?>)loads).size():-1);
        long[] fallback=cubiclab$fallback().summary();
        LabLodTrace.event("fallback",0,fallback[0],fallback[1],fallback[2],0);
    }
}
