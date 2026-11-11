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
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.client;

import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
@Mixin(ChunkCache.class)
public abstract class MixinChunkCache_HeightLimits {

    @Shadow public World world;

    @Expression("? >= @(0)")
    @ModifyExpressionValue(method = "getLightFor",
            at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    private int getLightFor_getMinHeight(int orig) {
        return ((ICubicWorld) world).getMinHeight();
    }

    @ModifyExpressionValue(method = "getLightFor", at = @At(value = "CONSTANT", args = "intValue=256"))
    private int getLightFor_getMaxHeight(int orig) {
        return ((ICubicWorld) world).getMaxHeight();
    }

    @Expression("? >= @(0)")
    @ModifyExpressionValue(method = "getLightForExt",
            at = @At(value = "MIXINEXTRAS:EXPRESSION"),
            slice = @Slice(
                    from = @At(value = "INVOKE:FIRST", target = "Lnet/minecraft/util/math/BlockPos;getY()I"),
                    to = @At(value = "INVOKE:FIRST",
                            target = "Lnet/minecraft/world/ChunkCache;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;")
            )
    )
    private int getLightForExt_getMinHeight(int orig) {
        return ((ICubicWorld) world).getMinHeight();
    }

    @ModifyExpressionValue(method = "getLightForExt", at = @At(value = "CONSTANT", args = "intValue=256"))
    private int getLightForExt_getMaxHeight(int orig) {
        return ((ICubicWorld) world).getMaxHeight();
    }
}
