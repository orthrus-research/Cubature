package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.ShadowPagingRegion;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageTrace;
import java.io.IOException;
import java.nio.channels.FileChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value=ShadowPagingRegion.class,remap=false)
public abstract class MixinStorageRegionForces {
    @Redirect(method="writeValues",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void payload(FileChannel channel,boolean metadata)throws IOException{force("payloadForce",channel,metadata);}
    @Redirect(method="doPendingHeaderUpdatesPass",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void header(FileChannel channel,boolean metadata)throws IOException{force("headerForce",channel,metadata);}
    @Redirect(method="writeSpecial",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void special(FileChannel channel,boolean metadata)throws IOException{force("specialForce",channel,metadata);}
    @Redirect(method="flush",at=@At(value="INVOKE",target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void flush(FileChannel channel,boolean metadata)throws IOException{force("regionFlushForce",channel,metadata);}
    private void force(String kind,FileChannel channel,boolean metadata)throws IOException{
        LabStorageTrace.Token token=LabStorageTrace.begin(kind,this);boolean ok=false;
        try{io.github.opencubicchunks.cubicchunks.lab.LabStorageFaults.before(this,kind);channel.force(metadata);ok=true;}finally{LabStorageTrace.end(token,ok,0);}
    }
}
