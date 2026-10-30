package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Independently authored read-only observer of DH's client quad tree: data wanted, reloads and queue depths. */
@Mixin(targets="com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree",remap=false)
public abstract class MixinLodQuadTree {
    @Shadow private ArrayList<?> enabledSections;
    @Shadow @Final private Set<Long> missingGenerationPosSet;
    @Shadow @Final private Set<Long> queuedGenerationPosSet;
    @Unique private long cubiclab$lastTree;
    @Inject(method="tryQueuePosForRetrieval",at=@At("HEAD"))
    private void cubiclab$wanted(long pos,CallbackInfo ci){LabLodTrace.event("retrieval-wanted",pos);}
    @Inject(method="queuePosToReload",at=@At("HEAD"))
    private void cubiclab$reload(long pos,CallbackInfo ci){LabLodTrace.event("reload-queued",pos);}
    /** At most 4 Hz: a enabled sections, b missing and c queued retrieval positions, d sections waiting to load. */
    @Inject(method="updateAllRenderSections",at=@At("RETURN"))
    private void cubiclab$tree(CallbackInfo ci){
        long now=System.nanoTime();
        if(now-cubiclab$lastTree<250_000_000L)return;
        cubiclab$lastTree=now;
        Object loads=LabLodTrace.call(LabLodTrace.field(this,"tickNodeHolder"),"getLoadSections");
        LabLodTrace.event("tree",0,enabledSections.size(),missingGenerationPosSet.size(),queuedGenerationPosSet.size(),
            loads instanceof Collection?((Collection<?>)loads).size():-1);
    }
}
