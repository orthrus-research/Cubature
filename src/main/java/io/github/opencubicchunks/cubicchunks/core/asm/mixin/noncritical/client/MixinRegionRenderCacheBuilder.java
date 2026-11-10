package io.github.opencubicchunks.cubicchunks.core.asm.mixin.noncritical.client;

import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Starts each chunk-mesh builder's layer buffers small. Vanilla reserves 10 MiB of direct memory
 * per builder (8 MiB of it for the solid layer) and keeps about ten builders per processor, capped
 * at 30% of the heap: 0.8 GiB on 8 processors and 2.3 GiB on 32, almost all of it never written.
 * A {@code BufferBuilder} grows on demand in 2 MiB steps and keeps what it grew to, so a builder
 * reserves only what the meshes it has built needed.
 */
@Mixin(RegionRenderCacheBuilder.class)
public class MixinRegionRenderCacheBuilder {
    /** Initial solid-layer capacity, in ints: 512 KiB instead of 8 MiB. */
    private static final int SOLID_INTS = 131072;
    /** Initial cutout and cutout-mipped capacity, in ints: 128 KiB instead of 512 KiB. */
    private static final int CUTOUT_INTS = 32768;
    /** Initial translucent capacity, in ints: 256 KiB instead of 1 MiB. */
    private static final int TRANSLUCENT_INTS = 65536;

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 2097152))
    private int cubicchunks$solidCapacity(int vanilla) {
        return SOLID_INTS;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 131072))
    private int cubicchunks$cutoutCapacity(int vanilla) {
        return CUTOUT_INTS;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 262144))
    private int cubicchunks$translucentCapacity(int vanilla) {
        return TRANSLUCENT_INTS;
    }
}
