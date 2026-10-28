package io.github.opencubicchunks.cubicchunks.lab.mixin;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.server.CubeWatcher;
import io.github.opencubicchunks.cubicchunks.lab.LabDeliveryTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=CubeWatcher.class,remap=false)
public abstract class MixinDeliveryWatcher {
    @Shadow private CubePos cubePos;
    @Inject(method="<init>",at=@At("RETURN"))
    private void cubiclab$request(CallbackInfo ci){record("watcher-created");}
    @Inject(method="providePlayerCube",at=@At("HEAD"))
    private void cubiclab$provide(boolean generate,CallbackInfoReturnable<Boolean> ci){record(generate?"provide-light-start":"provide-load-start");}
    @Inject(method="providePlayerCube",at=@At("RETURN"))
    private void cubiclab$provided(boolean generate,CallbackInfoReturnable<Boolean> ci){record(ci.getReturnValue()?"provide-ready":"provide-waiting");}
    @Inject(method="onCubeSent",at=@At("RETURN"))
    private void cubiclab$sent(CallbackInfo ci){record("packet-enqueued");}
    @Inject(method="invalidate",at=@At("HEAD"))
    private void cubiclab$retired(CallbackInfo ci){record("watcher-invalidated");}
    private void record(String op){LabDeliveryTrace.event(op,cubePos.getX(),cubePos.getY(),cubePos.getZ(),System.identityHashCode(this),0);}
}
