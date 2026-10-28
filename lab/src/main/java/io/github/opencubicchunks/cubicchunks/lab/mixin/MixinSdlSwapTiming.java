package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabRenderDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.cleanroommc.client.sdl.Window", remap = false)
public abstract class MixinSdlSwapTiming {
    @Inject(method = "swapBuffers()Lcom/cleanroommc/client/sdl/Window;", at = @At("HEAD"), remap = false)
    private void cubiclab$begin(CallbackInfoReturnable<Object> ci) { LabRenderDiagnostics.begin(LabRenderDiagnostics.SDL_SWAP); }
    @Inject(method = "swapBuffers()Lcom/cleanroommc/client/sdl/Window;", at = @At("RETURN"), remap = false)
    private void cubiclab$end(CallbackInfoReturnable<Object> ci) { LabRenderDiagnostics.end(LabRenderDiagnostics.SDL_SWAP); }
}
