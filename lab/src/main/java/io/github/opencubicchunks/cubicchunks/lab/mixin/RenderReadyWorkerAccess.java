package io.github.opencubicchunks.cubicchunks.lab.mixin;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ChunkRenderWorker.class)
public interface RenderReadyWorkerAccess {
    @Invoker("processTask") void cubiclab$processTask(ChunkCompileTaskGenerator task)throws InterruptedException;
}
