package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.ShadowPagingRegion;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageFaults;
import cubicchunks.regionlib.util.Utils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=ShadowPagingRegion.class,remap=false)
public abstract class MixinStorageWrites {
    @WrapOperation(method="writeDataPass",at=@At(value="INVOKE",target="Lcubicchunks/regionlib/util/Utils;writeFully(Ljava/nio/channels/GatheringByteChannel;[Ljava/nio/ByteBuffer;)V"))
    private void payload(GatheringByteChannel channel, ByteBuffer[] buffers, Operation<Void> original)throws IOException{
        LabStorageFaults.payload(this,channel,buffers);original.call(channel,buffers);
    }
    @WrapOperation(method="updateHeaders",at=@At(value="INVOKE",target="Lcubicchunks/regionlib/util/Utils;writeFully(Ljava/nio/channels/ByteChannel;Ljava/nio/ByteBuffer;)V"))
    private void header(ByteChannel channel, ByteBuffer buffer, Operation<Void> original)throws IOException{
        LabStorageFaults.header(this,channel,buffer);original.call(channel,buffer);
    }
}
