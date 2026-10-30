package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Independently authored read-only observer of one DH render section's build, upload and selection. */
@Mixin(targets="com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection",remap=false)
public abstract class MixinLodSection {
    @Shadow @Final public long pos;
    @Shadow public abstract boolean canRender();
    @Shadow public abstract boolean gpuUploadComplete();
    @Unique private int cubiclab$lodState=-1;
    @Inject(method="<init>",at=@At("RETURN"))
    private void cubiclab$created(CallbackInfo ci){LabLodTrace.event("section-new",pos);}
    @Inject(method="uploadRenderDataToGpuAsync",at=@At("RETURN"))
    private void cubiclab$queued(CallbackInfoReturnable<Boolean> cir){if(cir.getReturnValueZ())LabLodTrace.event("build-queued",pos);}
    @Inject(method="getAndBuildRenderData",at=@At("HEAD"))
    private void cubiclab$buildStart(CallbackInfoReturnable<?> cir){LabLodTrace.event("build-start",pos);}
    /** a=1 when DH built quads, 0 when the section had nothing to build. */
    @Inject(method="getAndBuildRenderData",at=@At("RETURN"))
    private void cubiclab$buildEnd(CallbackInfoReturnable<?> cir){LabLodTrace.event("build-end",pos,cir.getReturnValue()!=null?1:0);}
    /** a, b: opaque and transparent vertex bytes handed to the upload. */
    @Inject(method="uploadToGpuAsync",at=@At("HEAD"))
    private void cubiclab$uploadStart(CompletableFuture<Void> parent,ArrayList<ByteBuffer> opaque,ArrayList<ByteBuffer> transparent,CallbackInfoReturnable<?> cir){
        LabLodTrace.event("upload-start",pos,cubiclab$bytes(opaque),cubiclab$bytes(transparent),0,0);
    }
    /** a: 1 uploaded with geometry, 2 uploaded empty, 3 not uploaded, 0 no container, -1 failed. */
    @Inject(method="lambda$uploadToGpuAsync$0",at=@At("HEAD"))
    private void cubiclab$uploaded(CompletableFuture<Void> parent,@Coerce Object container,Throwable failure,CallbackInfo ci){
        int result=failure!=null?-1:container==null?0:
            !Boolean.TRUE.equals(LabLodTrace.field(container,"buffersUploaded"))?3:
            Boolean.TRUE.equals(LabLodTrace.call(container,"hasNonNullVbos"))?1:2;
        LabLodTrace.event("upload-done",pos,result);
    }
    /** a: bits 1 enabled, 2 canRender, 4 gpuUploadComplete; recorded when they change. */
    @Inject(method="setRenderingEnabled",at=@At("RETURN"))
    private void cubiclab$selected(boolean enabled,CallbackInfo ci){
        int state=(enabled?1:0)|(canRender()?2:0)|(gpuUploadComplete()?4:0);
        if(state!=cubiclab$lodState){cubiclab$lodState=state;LabLodTrace.event("render-state",pos,state);}
    }
    @Inject(method="close",at=@At("HEAD"))
    private void cubiclab$closed(CallbackInfo ci){LabLodTrace.event("section-close",pos);}
    @Unique private static long cubiclab$bytes(ArrayList<ByteBuffer> buffers){
        long total=0;if(buffers!=null)for(ByteBuffer b:buffers)if(b!=null)total+=b.remaining();return total;
    }
}
