package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabRenderDiagnostics;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkRenderWorker.class)
public abstract class MixinRenderWorkerStart {
    @Inject(method = "processTask", at = @At("HEAD"))
    private void cubiclab$start(ChunkCompileTaskGenerator task, CallbackInfo ci) { LabRenderDiagnostics.workerStart(task); }
}
