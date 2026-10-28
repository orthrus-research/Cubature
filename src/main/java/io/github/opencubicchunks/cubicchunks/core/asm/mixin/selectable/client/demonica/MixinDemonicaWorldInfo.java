/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Demonica 0.9.0 (its Iris shader pipeline).
 * No Demonica code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.demonica;

import java.util.function.IntSupplier;

import io.github.opencubicchunks.cubicchunks.core.compat.demonica.DemonicaCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Give shader packs the cubic world's floor as bedrockLevel, and the camera's depth below its column's surface. */
@Pseudo
@Mixin(targets = "net.coderbot.iris.uniforms.IrisExclusiveUniforms$WorldInfoUniforms", remap = false)
public abstract class MixinDemonicaWorldInfo {
    // Every int world-info uniform is registered through uniform1i(frequency, name, supplier); only
    // the one named bedrockLevel changes, so the order and number of registrations do not matter.
    @ModifyArgs(method = "addWorldInfoUniforms", at = @At(value = "INVOKE",
            target = "Lnet/coderbot/iris/gl/uniform/UniformHolder;uniform1i("
                    + "Lnet/coderbot/iris/gl/uniform/UniformUpdateFrequency;Ljava/lang/String;Ljava/util/function/IntSupplier;)"
                    + "Lnet/coderbot/iris/gl/uniform/UniformHolder;"),
            require = 1, remap = false)
    private static void cubicchunks$bedrockLevel(Args args) {
        if ("bedrockLevel".equals(args.get(1))) {
            IntSupplier vanilla = args.get(2);
            args.set(2, DemonicaCompat.bedrockLevel(vanilla));
        }
    }

    @Inject(method = "addWorldInfoUniforms", at = @At("TAIL"), require = 1, remap = false)
    private static void cubicchunks$surfaceDepth(/*UniformHolder*/ @Coerce Object uniforms, CallbackInfo ci) {
        DemonicaCompat.registerSurfaceDepth(uniforms);
    }
}
