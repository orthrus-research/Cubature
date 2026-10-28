package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.lab.LabHooks;
import io.github.opencubicchunks.cubicchunks.lab.LabDeliveryPacket;
import io.github.opencubicchunks.cubicchunks.lab.LabDeliveryTrace;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import org.spongepowered.asm.mixin.Shadow;
import io.netty.buffer.ByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "io.github.opencubicchunks.cubicchunks.core.network.PacketCubes", remap = false)
public abstract class MixinPacketCubes implements LabDeliveryPacket {
    @Shadow private CubePos[] cubePos;
    public CubePos[] cubiclab$positions(){return cubePos;}
    @Inject(method = "toBytes", at = @At("HEAD"), remap = false)
    private void cubiclab$encodeBegin(ByteBuf buf, CallbackInfo ci) {
        LabHooks.encode.remove();
        LabHooks.PacketCounters counters = LabHooks.serverPackets;
        if (counters != null) LabHooks.encode.set(new LabHooks.Invocation(counters, buf.writerIndex(), 0));
    }
    @Inject(method = "toBytes", at = @At("RETURN"), remap = false)
    private void cubiclab$encodeEnd(ByteBuf buf, CallbackInfo ci) {
        LabHooks.Invocation invocation = LabHooks.encode.get(); LabHooks.encode.remove();
        if (invocation != null) invocation.counters.record(System.nanoTime() - invocation.started,
            buf.writerIndex() - invocation.index, buf.getUnsignedShort(invocation.index));
    }
    @Inject(method = "fromBytes", at = @At("HEAD"), remap = false)
    private void cubiclab$decodeBegin(ByteBuf buf, CallbackInfo ci) {
        LabHooks.decode.remove();
        LabHooks.PacketCounters counters = LabHooks.clientPackets;
        if (counters != null) LabHooks.decode.set(new LabHooks.Invocation(counters, buf.readerIndex(), buf.getUnsignedShort(buf.readerIndex())));
    }
    @Inject(method = "fromBytes", at = @At("RETURN"), remap = false)
    private void cubiclab$decodeEnd(ByteBuf buf, CallbackInfo ci) {
        LabDeliveryTrace.packet("packet-decoded",this);
        LabHooks.Invocation invocation = LabHooks.decode.get(); LabHooks.decode.remove();
        if (invocation != null) invocation.counters.record(System.nanoTime() - invocation.started,
            buf.readerIndex() - invocation.index, invocation.cubes);
    }
}
