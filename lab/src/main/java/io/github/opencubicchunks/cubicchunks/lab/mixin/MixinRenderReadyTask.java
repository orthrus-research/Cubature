package io.github.opencubicchunks.cubicchunks.lab.mixin;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderReadyHooks;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChunkCompileTaskGenerator.class)
public abstract class MixinRenderReadyTask {
    @Inject(method="finish",at=@At("RETURN"))private void cubiclab$cancelled(CallbackInfo ci){LabRenderReadyHooks.cancelled((ChunkCompileTaskGenerator)(Object)this);}
}
