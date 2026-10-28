package io.github.opencubicchunks.cubicchunks.lab.mixin;
import io.github.opencubicchunks.cubicchunks.lab.LabFullViewRenderReady;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MixinRenderReadyFrame {
    @Inject(method="updateDisplay",at=@At("HEAD"))private void cubiclab$frameCommands(CallbackInfo ci){LabFullViewRenderReady.afterFrameCommands();}
}
