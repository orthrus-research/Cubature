package io.github.opencubicchunks.cubicchunks.lab.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderDiagnostics;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderReadyHooks;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.concurrent.PriorityBlockingQueue;
/** One publication hook shared by optional timing and readiness, avoiding competing redirects. */
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinRenderTaskOffer {
    @WrapOperation(method={"updateChunkLater","updateTransparencyLater"},at=@At(value="INVOKE",target="Ljava/util/concurrent/PriorityBlockingQueue;offer(Ljava/lang/Object;)Z",remap=false))
    private boolean cubiclab$offer(PriorityBlockingQueue<Object> queue, Object value, Operation<Boolean> original){
        ChunkCompileTaskGenerator task=(ChunkCompileTaskGenerator)value;
        long timing=LabRenderDiagnostics.enqueue(task),token=LabRenderReadyHooks.beforeOffer(task);boolean accepted=false;
        try{accepted=original.call(queue,value);return accepted;}
        finally{LabRenderDiagnostics.enqueueResult(task,timing,accepted);LabRenderReadyHooks.afterOffer(task,token,accepted);}
    }
}
