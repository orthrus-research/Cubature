package io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.common;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.core.server.TickDiagnostics;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

/** Applies first, placing the core measurement inside the lab's independent complete-tick wrapper. */
@Mixin(value = MinecraftServer.class, priority = 1100)
public abstract class MixinServerTickCosts {
    @WrapMethod(method = "tick")
    private void cubicchunks$measureTick(Operation<Void> original) {
        TickDiagnostics.beginTick();
        try {
            original.call();
        } finally {
            TickDiagnostics.endTick();
        }
    }
}
