package io.github.opencubicchunks.cubicchunks.lab.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.ShadowPagingRegion;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import java.io.IOException;
import java.nio.channels.FileChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=ShadowPagingRegion.class,remap=false)
public abstract class MixinStorageRegionForces {
    @WrapOperation(method="writeValues",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void payload(FileChannel channel, boolean metadata, Operation<Void> original)throws IOException{force("payloadForce",channel,metadata,original);}
    @WrapOperation(method="doPendingHeaderUpdatesPass",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void header(FileChannel channel, boolean metadata, Operation<Void> original)throws IOException{force("headerForce",channel,metadata,original);}
    @WrapOperation(method="writeSpecial",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void special(FileChannel channel, boolean metadata, Operation<Void> original)throws IOException{force("specialForce",channel,metadata,original);}
    @WrapOperation(method="flush",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void flush(FileChannel channel, boolean metadata, Operation<Void> original)throws IOException{force("regionFlushForce",channel,metadata,original);}
    private void force(String kind,FileChannel channel,boolean metadata,Operation<Void> original)throws IOException{
        LabStorageTrace.Token token=LabStorageTrace.begin(kind,this);boolean ok=false;
        try{io.github.opencubicchunks.cubicchunks.lab.LabStorageFaults.before(this,kind);original.call(channel,metadata);ok=true;}finally{LabStorageTrace.end(token,ok,0);}
    }
}
