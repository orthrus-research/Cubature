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
package io.github.opencubicchunks.cubicchunks.core.world;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.world.IHeightMap;
import io.github.opencubicchunks.cubicchunks.core.lighting.LightingManager;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.ByteBuffer;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ClientHeightMap implements IHeightMap {

    private final Chunk column;
    private final HeightMap hmap;
    private int heightMapLowest = Coords.NO_HEIGHT;

    public ClientHeightMap(Chunk column, int[] heightmap) {
        this.column = column;
        this.hmap = new HeightMap(heightmap);
    }

    @Override
    public void onOpacityChange(int localX, int blockY, int localZ, int opacity) {
        int oldTopY = getTopBlockY(localX, localZ);
        //to avoid unnecessary delay when breaking blocks client needs to figure out new height before
        //server tells the client what it is
        //common cases first
        if (opacity != 0) {
            if (blockY > oldTopY) setHeight(localX, localZ, blockY);
            return;
        }
        if (blockY != oldTopY) {
            //if not breaking the top block - no changes
            return;
        }
        //changed the top block
        int newTop = oldTopY - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(localX, newTop, localZ);
        while (column.getBlockLightOpacity(pos) == 0 && newTop > oldTopY - LightingManager.MAX_CLIENT_LIGHT_SCAN_DEPTH) {
            newTop--;
            pos.setY(newTop);
        }
        //update the heightmap. If this update it not accurate - it will be corrected when server sends block update
        this.setHeight(localX, localZ, newTop);
    }

    @Override
    public int getTopBlockY(int localX, int localZ) {
        return hmap.get(getIndex(localX, localZ));
    }

    @Override
    public int getLowestTopBlockY() {
        if (heightMapLowest == Coords.NO_HEIGHT) {
            heightMapLowest = Integer.MAX_VALUE;
            for (int i = 0; i < Cube.SIZE * Cube.SIZE; i++) {
                if (hmap.get(i) < heightMapLowest) {
                    heightMapLowest = hmap.get(i);
                }
            }
        }
        return heightMapLowest;
    }

    @SuppressWarnings("deprecation")
    @Override
    public int getTopBlockYBelow(int localX, int localZ, int blockY) {
        throw new UnsupportedOperationException("Not implemented");
    }

    public void setHeight(int localX, int localZ, int height) {
        hmap.set(getIndex(localX, localZ), height);
        heightMapLowest = Coords.NO_HEIGHT;
    }

    public byte[] getData() {
        ByteBuffer data = ByteBuffer.allocate(Cube.SIZE * Cube.SIZE * Integer.BYTES);
        for (int i = 0; i < Cube.SIZE * Cube.SIZE; i++) {
            data.putInt(hmap.get(i));
        }
        return data.array();
    }

    public void loadData(PacketBuffer in) {
        heightMapLowest = Coords.NO_HEIGHT;
        for (int i = 0; i < Cube.SIZE * Cube.SIZE; i++) {
            hmap.set(i, in.readInt());
        }
    }

    private static int getIndex(int localX, int localZ) {
        return (localZ << 4) | localX;
    }
}
