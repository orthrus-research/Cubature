package io.github.opencubicchunks.cubicchunks.lab.mixin;
import io.github.opencubicchunks.cubicchunks.lab.LabRenderReadyHooks;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(RenderChunk.class)
public abstract class MixinRenderReadyChunk {
    @Inject(method="setNeedsUpdate",at=@At("RETURN"))private void cubiclab$dirty(boolean immediate,CallbackInfo ci){LabRenderReadyHooks.dirty((RenderChunk)(Object)this);}
    @Inject(method="setCompiledChunk",at=@At("RETURN"))private void cubiclab$compiled(CompiledChunk chunk,CallbackInfo ci){LabRenderReadyHooks.dirty((RenderChunk)(Object)this);LabRenderReadyHooks.trace("mesh-installed",(RenderChunk)(Object)this,chunk==CompiledChunk.DUMMY?0:1);}
}
