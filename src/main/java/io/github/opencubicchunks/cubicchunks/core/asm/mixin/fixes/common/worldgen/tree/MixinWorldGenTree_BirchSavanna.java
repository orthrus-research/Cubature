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
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.fixes.common.worldgen.tree;

import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenBirchTree;
import net.minecraft.world.gen.feature.WorldGenSavannaTree;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Random;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@Mixin({WorldGenBirchTree.class, WorldGenSavannaTree.class})
public class MixinWorldGenTree_BirchSavanna {

    // the ordinal=0 is boolean flag = true
    @ModifyExpressionValue(method = "generate", at = @At(value = "CONSTANT", args = "intValue=1", ordinal = 1))
    private int getMinGenHeight(int orig, World worldIn, Random rand, BlockPos position) {
        return ((ICubicWorld) worldIn).getMinHeight() + 1;
    }

    @Expression("? >= @(0)")
    @ModifyExpressionValue(method = "generate",
                    at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    private int getMinGenHeightCompareZero(int orig, World worldIn, Random rand, BlockPos position) {
        return ((ICubicWorld) worldIn).getMinHeight();
    }

    @ModifyExpressionValue(method = "generate", at = @At(value = "CONSTANT", args = "intValue=256"))
    private int getMaxGenHeight(int orig, World worldIn, Random rand, BlockPos position) {
        return ((ICubicWorld) worldIn).getMaxHeight();
    }
}
