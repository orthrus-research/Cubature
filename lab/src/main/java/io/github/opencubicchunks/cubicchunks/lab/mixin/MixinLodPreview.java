package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabLodTrace;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observer of Cubature's seed-only surface preview, the generator DH calls for every distant tile:
 * queued on DH's executor, sampling started on a worker, sampling finished, future completed.
 */
@Mixin(targets="io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.CubicTerrainPreviewGenerator",remap=false)
public abstract class MixinLodPreview {
    @Unique private static final ThreadLocal<long[]> cubiclab$started=ThreadLocal.withInitial(()->new long[1]);
    /** a: DH data detail; the future's completion records preview-done, a 0 ok, -1 failed, -2 cancelled. */
    @Inject(method="generateLod",at=@At("RETURN"))
    private void cubiclab$queued(int minChunkX,int minChunkZ,int lodX,int lodZ,byte detail,@Coerce Object data,@Coerce Object mode,
            ExecutorService executor,Consumer<?> consumer,CallbackInfoReturnable<CompletableFuture<Void>> cir){
        long pos=LabLodTrace.encode(detail+6,lodX,lodZ);
        LabLodTrace.event("preview-queued",pos,detail);
        CompletableFuture<Void> future=cir.getReturnValue();
        if(future!=null)future.whenComplete((ignored,failure)->{
            Throwable cause=failure instanceof CompletionException&&failure.getCause()!=null?failure.getCause():failure;
            LabLodTrace.event("preview-done",pos,failure==null?0:cause instanceof CancellationException?-2:-1);
        });
    }
    @Inject(method="lambda$generateLod$0",at=@At("HEAD"))
    private void cubiclab$sampleStart(byte detail,@Coerce Object data,int minChunkX,int minChunkZ,Consumer<?> consumer,CallbackInfo ci){
        cubiclab$started.get()[0]=System.nanoTime();
        LabLodTrace.event("preview-start",cubiclab$pos(detail,minChunkX,minChunkZ));
    }
    /** a: sampling nanoseconds on this worker. */
    @Inject(method="lambda$generateLod$0",at=@At("RETURN"))
    private void cubiclab$sampleEnd(byte detail,@Coerce Object data,int minChunkX,int minChunkZ,Consumer<?> consumer,CallbackInfo ci){
        LabLodTrace.event("preview-end",cubiclab$pos(detail,minChunkX,minChunkZ),System.nanoTime()-cubiclab$started.get()[0]);
    }
    /** A tile at data detail d is a section of detail d+6, 64 cells of 2^d blocks wide. */
    @Unique private static long cubiclab$pos(byte detail,int minChunkX,int minChunkZ){
        int width=64<<detail;
        return LabLodTrace.encode(detail+6,Math.floorDiv(minChunkX*16,width),Math.floorDiv(minChunkZ*16,width));
    }
}
