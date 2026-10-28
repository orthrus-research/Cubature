package io.github.opencubicchunks.cubicchunks.lab.mixin;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderReadyHooks;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinRenderReadyDispatcher {
    @Redirect(method={"updateChunkNow","runChunkUploads"},at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/ChunkRenderWorker;processTask(Lnet/minecraft/client/renderer/chunk/ChunkCompileTaskGenerator;)V"))
    private void cubiclab$process(ChunkRenderWorker worker,ChunkCompileTaskGenerator task)throws InterruptedException{LabRenderReadyHooks.process(worker,task);}
}
