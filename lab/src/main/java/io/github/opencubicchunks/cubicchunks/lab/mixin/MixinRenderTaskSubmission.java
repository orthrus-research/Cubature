package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabRenderDiagnostics;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinRenderTaskSubmission {
    @Inject(method = "uploadDisplayList", at = @At("HEAD"))
    private void cubiclab$displayBegin(BufferBuilder builder, int list, RenderChunk chunk, CallbackInfo ci) {
        LabRenderDiagnostics.begin(LabRenderDiagnostics.DISPLAY_LIST);
    }
    @Inject(method = "uploadDisplayList", at = @At("RETURN"))
    private void cubiclab$displayEnd(BufferBuilder builder, int list, RenderChunk chunk, CallbackInfo ci) {
        LabRenderDiagnostics.end(LabRenderDiagnostics.DISPLAY_LIST);
    }
}
