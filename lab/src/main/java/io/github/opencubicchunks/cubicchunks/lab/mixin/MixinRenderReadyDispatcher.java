package io.github.opencubicchunks.cubicchunks.lab.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderReadyHooks;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinRenderReadyDispatcher {
    @WrapOperation(method={"updateChunkNow","runChunkUploads"},at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/ChunkRenderWorker;processTask(Lnet/minecraft/client/renderer/chunk/ChunkCompileTaskGenerator;)V"))
    private void cubiclab$process(ChunkRenderWorker worker, ChunkCompileTaskGenerator task, Operation<Void> original)throws InterruptedException{LabRenderReadyHooks.process(worker,task);}
}
