package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.CubicChunkSnapshot;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace vanilla column readers with owner-thread cube snapshots on both sides. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.api.internal.SharedApi",remap=false)
public abstract class MixinDistantHorizonsUpdates {
    @Inject(method="applyChunkUpdate",at=@At("HEAD"),cancellable=true,remap=false)
    private void cubicUpdate(IChunkWrapper wrapper,ILevelWrapper level,boolean wait,CallbackInfo callback) {
        if (!(wrapper instanceof ChunkWrapper) || wrapper instanceof CubicChunkSnapshot) return;
        Chunk column=((ChunkWrapper)wrapper).getChunk();
        if (column!=null && ((ICubicWorld)column.getWorld()).isCubicWorld()) {
            DistantHorizonsCompat.columnLoaded(column);callback.cancel();
        }
    }
}
