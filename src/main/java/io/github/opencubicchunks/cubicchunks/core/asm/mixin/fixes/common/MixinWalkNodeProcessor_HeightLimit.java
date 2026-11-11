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
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.fixes.common;

import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.IMinMaxHeight;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.NodeProcessor;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@Mixin(WalkNodeProcessor.class)
public abstract class MixinWalkNodeProcessor_HeightLimit extends NodeProcessor {

    @Expression("? > @(0)")
    @ModifyExpressionValue(method = "getStart", at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    private int getMinHeight_GetStart(int originalY) {
        return ((ICubicWorld) this.entity.world).getMinHeight() + originalY;
    }

    // redirect getBlockState and check if the block is loaded
    @WrapOperation(method = "getStart", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/IBlockAccess;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"))
    private IBlockState getLoadedBlockState_getStart(IBlockAccess access, BlockPos pos, Operation<IBlockState> original) {
        if (!entity.world.isBlockLoaded(pos)) {
            return Blocks.BEDROCK.getDefaultState();
        }
        return original.call(access, pos);
    }


    @Expression("? > @(0)")
    @ModifyExpressionValue(
            method = "getSafePoint",
            at = @At(value = "MIXINEXTRAS:EXPRESSION", ordinal = 1))
    private int getMinHeight_GetSafePoint(int originalY) {
        return ((ICubicWorld) this.entity.world).getMinHeight() + originalY;
    }

    @ModifyExpressionValue(
            method = "getPathNodeType(Lnet/minecraft/world/IBlockAccess;III)Lnet/minecraft/pathfinding/PathNodeType;",
            at = @At(value = "CONSTANT", args = "intValue=1", ordinal = 0))
    private int getMinHeight_GetPathNodeType(int originalY, IBlockAccess blockaccessIn, int x, int y, int z) {
        return ((IMinMaxHeight) blockaccessIn).getMinHeight() + originalY;
    }
}
