package io.github.opencubicchunks.cubicchunks.lab.mixin;

import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zone.rong.imaginebreaker.Index;

/** Restore Field.set's initialization semantics before the 0.7 unsafe holder write. */
@Mixin(targets = "net.minecraftforge.registries.ObjectHolderRef", remap = false)
public abstract class MixinObjectHolderRef {
    @Shadow(remap = false) private Field field;
    @Inject(method = "apply", at = @At("HEAD"), remap = false)
    private void cubiclab$initializeHolder(CallbackInfo callback) {
        if (field != null) Index.get().ensureInitialized(field.getDeclaringClass());
    }
}
