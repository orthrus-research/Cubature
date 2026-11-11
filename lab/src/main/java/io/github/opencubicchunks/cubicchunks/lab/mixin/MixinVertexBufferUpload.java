package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabRenderDiagnostics;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(VertexBuffer.class)
public abstract class MixinVertexBufferUpload {
    @Inject(method = "bufferData", at = @At("HEAD"))
    private void cubiclab$begin(ByteBuffer data, CallbackInfo ci) { LabRenderDiagnostics.begin(LabRenderDiagnostics.VBO_UPLOAD); }
    @Inject(method = "bufferData", at = @At("RETURN"))
    private void cubiclab$end(ByteBuffer data, CallbackInfo ci) { LabRenderDiagnostics.end(LabRenderDiagnostics.VBO_UPLOAD); }
}
