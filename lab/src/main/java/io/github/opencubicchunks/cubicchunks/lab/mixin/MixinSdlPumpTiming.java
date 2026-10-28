package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabRenderDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.cleanroommc.client.sdl.Pump", remap = false)
public abstract class MixinSdlPumpTiming {
    @Inject(method = "pump(Lcom/cleanroommc/client/sdl/Window;)V", at = @At("HEAD"), remap = false)
    private static void cubiclab$begin(@Coerce Object window, CallbackInfo ci) { LabRenderDiagnostics.begin(LabRenderDiagnostics.SDL_PUMP); }
    @Inject(method = "pump(Lcom/cleanroommc/client/sdl/Window;)V", at = @At("RETURN"), remap = false)
    private static void cubiclab$end(@Coerce Object window, CallbackInfo ci) { LabRenderDiagnostics.end(LabRenderDiagnostics.SDL_PUMP); }
}
