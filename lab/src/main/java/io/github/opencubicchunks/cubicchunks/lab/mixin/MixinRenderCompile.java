package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabRenderDiagnostics;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderChunk.class)
public abstract class MixinRenderCompile {
    @Inject(method = "rebuildChunk", at = @At("HEAD"))
    private void cubiclab$begin(float x, float y, float z, ChunkCompileTaskGenerator task, CallbackInfo ci) {
        LabRenderDiagnostics.begin(LabRenderDiagnostics.REBUILD);
    }
    @Inject(method = "rebuildChunk", at = @At("RETURN"))
    private void cubiclab$end(float x, float y, float z, ChunkCompileTaskGenerator task, CallbackInfo ci) {
        LabRenderDiagnostics.end(LabRenderDiagnostics.REBUILD);
    }
}
