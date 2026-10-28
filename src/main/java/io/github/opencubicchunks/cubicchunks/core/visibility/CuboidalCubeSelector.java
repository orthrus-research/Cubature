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
package io.github.opencubicchunks.cubicchunks.core.visibility;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.util.math.ChunkPos;

import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class CuboidalCubeSelector extends CubeSelector {

    @Override
    public void forAllVisibleFrom(CubePos cubePos, int horizontalViewDistance, int verticalViewDistance, Consumer<CubePos> consumer) {
        int cubeX = cubePos.getX();
        int cubeY = cubePos.getY();
        int cubeZ = cubePos.getZ();
        for (int x = cubeX - horizontalViewDistance; x <= cubeX + horizontalViewDistance; x++) {
            for (int y = cubeY - verticalViewDistance; y <= cubeY + verticalViewDistance; y++) {
                for (int z = cubeZ - horizontalViewDistance; z <= cubeZ + horizontalViewDistance; z++) {
                    consumer.accept(new CubePos(x, y, z));
                }
            }
        }
    }

    @Override
    public void findChanged(CubePos oldPos, CubePos newPos,
            int horizontalViewDistance, int verticalViewDistance,
            Set<CubePos> cubesToRemove, Set<CubePos> cubesToLoad,
            Set<ChunkPos> columnsToRemove, Set<ChunkPos> columnsToLoad) {
        if (oldPos.equals(newPos)) {
            return;
        }
        addCubeDifference(newPos, horizontalViewDistance, verticalViewDistance,
                oldPos, horizontalViewDistance, verticalViewDistance, cubesToLoad);
        addCubeDifference(oldPos, horizontalViewDistance, verticalViewDistance,
                newPos, horizontalViewDistance, verticalViewDistance, cubesToRemove);
        addColumnDifference(newPos, horizontalViewDistance, oldPos, horizontalViewDistance, columnsToLoad);
        addColumnDifference(oldPos, horizontalViewDistance, newPos, horizontalViewDistance, columnsToRemove);

        assert cubesToLoad.stream().allMatch(pos -> !cubesToRemove.contains(pos)) : "cubesToRemove contains element from cubesToLoad!";
        assert columnsToLoad.stream().allMatch(pos -> !columnsToRemove.contains(pos)) : "columnsToRemove contains element from columnsToLoad!";
    }

    @Override
    public void findAllUnloadedOnViewDistanceDecrease(CubePos playerPos,
            int oldHorizontalViewDistance, int newHorizontalViewDistance,
            int oldVerticalViewDistance, int newVerticalViewDistance,
            Set<CubePos> cubesToUnload, Set<ChunkPos> columnsToUnload) {
        addCubeDifference(playerPos, oldHorizontalViewDistance, oldVerticalViewDistance,
                playerPos, newHorizontalViewDistance, newVerticalViewDistance, cubesToUnload);
        addColumnDifference(playerPos, oldHorizontalViewDistance, playerPos, newHorizontalViewDistance, columnsToUnload);
    }

    /** Emit from minus excluded as at most six disjoint slabs, without visiting their shared interior. */
    private static void addCubeDifference(CubePos from, int horizontal, int vertical,
            CubePos excluded, int excludedHorizontal, int excludedVertical, Set<CubePos> result) {
        // Half-open long bounds keep center subtraction and the inclusive upper endpoint from overflowing.
        long minX = (long) from.getX() - horizontal, maxX = (long) from.getX() + horizontal + 1;
        long minY = (long) from.getY() - vertical, maxY = (long) from.getY() + vertical + 1;
        long minZ = (long) from.getZ() - horizontal, maxZ = (long) from.getZ() + horizontal + 1;
        long lowX = Math.max(minX, (long) excluded.getX() - excludedHorizontal);
        long highX = Math.min(maxX, (long) excluded.getX() + excludedHorizontal + 1);
        long lowY = Math.max(minY, (long) excluded.getY() - excludedVertical);
        long highY = Math.min(maxY, (long) excluded.getY() + excludedVertical + 1);
        long lowZ = Math.max(minZ, (long) excluded.getZ() - excludedHorizontal);
        long highZ = Math.min(maxZ, (long) excluded.getZ() + excludedHorizontal + 1);
        if (lowX >= highX || lowY >= highY || lowZ >= highZ) {
            addCubes(minX, maxX, minY, maxY, minZ, maxZ, result);
            return;
        }
        addCubes(minX, lowX, minY, maxY, minZ, maxZ, result);
        addCubes(highX, maxX, minY, maxY, minZ, maxZ, result);
        addCubes(lowX, highX, minY, lowY, minZ, maxZ, result);
        addCubes(lowX, highX, highY, maxY, minZ, maxZ, result);
        addCubes(lowX, highX, lowY, highY, minZ, lowZ, result);
        addCubes(lowX, highX, lowY, highY, highZ, maxZ, result);
    }

    private static void addCubes(long minX, long maxX, long minY, long maxY, long minZ, long maxZ, Set<CubePos> result) {
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
            return;
        }
        for (long x = minX; x < maxX; x++) {
            for (long z = minZ; z < maxZ; z++) {
                for (long y = minY; y < maxY; y++) {
                    result.add(new CubePos((int) x, (int) y, (int) z));
                }
            }
        }
    }

    /** Columns are the horizontal projection; vertical-only movement must not unload them. */
    private static void addColumnDifference(CubePos from, int horizontal,
            CubePos excluded, int excludedHorizontal, Set<ChunkPos> result) {
        long minX = (long) from.getX() - horizontal, maxX = (long) from.getX() + horizontal + 1;
        long minZ = (long) from.getZ() - horizontal, maxZ = (long) from.getZ() + horizontal + 1;
        long lowX = Math.max(minX, (long) excluded.getX() - excludedHorizontal);
        long highX = Math.min(maxX, (long) excluded.getX() + excludedHorizontal + 1);
        long lowZ = Math.max(minZ, (long) excluded.getZ() - excludedHorizontal);
        long highZ = Math.min(maxZ, (long) excluded.getZ() + excludedHorizontal + 1);
        if (lowX >= highX || lowZ >= highZ) {
            addColumns(minX, maxX, minZ, maxZ, result);
            return;
        }
        addColumns(minX, lowX, minZ, maxZ, result);
        addColumns(highX, maxX, minZ, maxZ, result);
        addColumns(lowX, highX, minZ, lowZ, result);
        addColumns(lowX, highX, highZ, maxZ, result);
    }

    private static void addColumns(long minX, long maxX, long minZ, long maxZ, Set<ChunkPos> result) {
        if (minX >= maxX || minZ >= maxZ) {
            return;
        }
        for (long x = minX; x < maxX; x++) {
            for (long z = minZ; z < maxZ; z++) {
                result.add(new ChunkPos((int) x, (int) z));
            }
        }
    }
}
