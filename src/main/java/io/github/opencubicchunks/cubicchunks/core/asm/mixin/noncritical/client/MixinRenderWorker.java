/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2019 OpenCubicChunks
 *  Copyright (c) 2015-2019 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.noncritical.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkRenderWorker.class)
public abstract class MixinRenderWorker {

    /**
     * A render chunk more than 24 blocks from the player is built only when its neighbors are loaded;
     * otherwise processTask drops the task unfinished and the chunk keeps the mesh it last compiled.
     * When the chunk's own cube has left, that mesh shows a cube that is gone, and with its
     * neighbors usually gone too nothing would ever replace it. Such a chunk is built as though it
     * were near: its cube reads as air, so it compiles to nothing.
     */
    /** A lab switch: leaves the game's wait in place, to show what it does without this fix. */
    private static final boolean WAIT_LIKE_VANILLA = Boolean.getBoolean("cubicchunks.noUnloadedMeshFix");

    @WrapOperation(method = "processTask", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/math/BlockPos;distanceSq(Lnet/minecraft/util/math/Vec3i;)D"))
    private double buildWhenOwnCubeGone(BlockPos center, Vec3i player, Operation<Double> original,
            @Local(argsOnly = true) ChunkCompileTaskGenerator task) {
        World world = task.getRenderChunk().getWorld();
        if (!WAIT_LIKE_VANILLA && world != null && ((ICubicWorld) world).isCubicWorld() && ((ICubicWorld) world).getCubeCache()
                .getLoadedCube(center.getX() >> 4, center.getY() >> 4, center.getZ() >> 4) == null) {
            return 0.0;
        }
        return original.call(center, player);
    }

    @WrapOperation(method = "processTask", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderWorker;isChunkExisting(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/World;)Z",
            ordinal = 0))
    private boolean onIsChunkExisting(ChunkRenderWorker chunkRenderWorker, BlockPos pos, World world, Operation<Boolean> original) {
        BlockPos.MutableBlockPos p = (BlockPos.MutableBlockPos) pos;
        if (((ICubicWorld) world).isCubicWorld()) {
            if (!original.call(chunkRenderWorker, p.move(EnumFacing.EAST, 16).move(EnumFacing.DOWN, 16), world)) {
                return false;
            }
            if (!original.call(chunkRenderWorker, p.move(EnumFacing.UP, 32), world)) {
                return false;
            }
            p.move(EnumFacing.DOWN, 16).move(EnumFacing.WEST, 16);
        }
        return original.call(chunkRenderWorker, p, world);
    }

    /**
     * @author Barteks2x
     * @reason use cubes in cubic chunks world
     */
    @Overwrite
    private boolean isChunkExisting(BlockPos pos, World worldIn) {
        if (((ICubicWorld) worldIn).isCubicWorld()) {
            return ((ICubicWorld) worldIn).getCubeCache()
                    .getLoadedCube(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4) != null;
        } else {
            return !worldIn.getChunk(pos.getX() >> 4, pos.getZ() >> 4).isEmpty();
        }
    }
}
