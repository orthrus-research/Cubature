package io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.common;

import io.github.opencubicchunks.cubicchunks.core.server.delivery.AutosaveScope;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The server's periodic save (scoped by MixinMinecraftServer) only selects a cubic world's dirty cubes and
 * columns; provider ticks write them within the autosave budget. The scope is read here, at the one call
 * vanilla's save makes, so every other saveChunks call, WorldEvent.Save handlers included, saves at once.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer_PeriodicAutosave {

    @Redirect(method = "saveAllChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/ChunkProviderServer;saveChunks(Z)Z"))
    private boolean cubicchunks$periodicAutosave(ChunkProviderServer provider, boolean all) {
        if (AutosaveScope.isActive() && provider instanceof CubeProviderServer) {
            ((CubeProviderServer) provider).beginPeriodicAutosave();
            return true;
        }
        return provider.saveChunks(all);
    }
}
