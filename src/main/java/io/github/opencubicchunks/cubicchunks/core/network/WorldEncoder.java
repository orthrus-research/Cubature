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
package io.github.opencubicchunks.cubicchunks.core.network;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.lighting.ILightingManager;
import io.github.opencubicchunks.cubicchunks.core.util.AddressTools;
import io.github.opencubicchunks.cubicchunks.core.world.ClientHeightMap;
import io.github.opencubicchunks.cubicchunks.core.world.IColumnInternal;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Collection;
import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
class WorldEncoder {

    static void encodeCubes(PacketBuffer out, Collection<Cube> cubes) {
        // write first all the flags, then all the block data, then all the light data etc for better compression

        // 1. emptiness
        cubes.forEach(cube -> {
            byte flags = 0;
            if(cube.isEmpty())
                flags |= 1;
            if(cube.getStorage() != null)
                flags |= 2;
            if(cube.getBiomeArray() != null)
                flags |= 4;
            out.writeByte(flags);
        });

        // 2. block IDs and metadata
        cubes.forEach(cube -> {
            if (!cube.isEmpty()) {
                //noinspection ConstantConditions
                cube.getStorage().getData().write(out);
            }
        });

        // 3. block light
        cubes.forEach(cube -> {
            if (cube.getStorage() != null) {
                out.writeBytes(cube.getStorage().getBlockLight().getData());
            }
        });

        // 4. sky light
        cubes.forEach(cube -> {
            if (cube.getStorage() != null && cube.getWorld().provider.hasSkyLight()) {
                out.writeBytes(cube.getStorage().getSkyLight().getData());
            }
        });

        // 5. heightmap and bottom-block-y. Each non-empty cube has a chance
        // to update this data.
        // trying to keep track of when it changes would be complex, so send
        // it wil all cubes
        cubes.forEach(cube -> {
            if (!cube.isEmpty()) {
                ((IColumnInternal) cube.getColumn()).writeHeightmapDataForClient(out);
            }
        });
        
        // 6. biomes
        cubes.forEach(cube -> {
            if (cube.getBiomeArray() != null)
                out.writeBytes(cube.getBiomeArray());
        });
    }

    static void encodeColumn(PacketBuffer out, Chunk column) {
        // 1. biomes
        out.writeBytes(column.getBiomeArray());
        ((IColumnInternal) column).writeHeightmapDataForClient(out);
    }

    static void decodeColumn(PacketBuffer in, Chunk column) {
        // 1. biomes
        in.readBytes(column.getBiomeArray());
        if (in.readableBytes() > 0) {
            ((IColumnInternal) column).loadClientHeightmapData(in);
        }
    }

    static void decodeCube(PacketBuffer in, List<Cube> cubes, boolean hasSkyLight) {
        // 1. emptiness
        byte[] flags = new byte[cubes.size()];
        in.readBytes(flags);
        for (int i = 0; i < cubes.size(); i++) {
            Cube cube = cubes.get(i);
            if (cube == null) {
                // The payload remains present even when its column was unloaded before application.
                // Retain the original flags so every later field and cube keeps its wire alignment.
                continue;
            }
            cube.setClientCube();
            if ((flags[i] & 2) != 0) {
                ExtendedBlockStorage storage = new ExtendedBlockStorage(Coords.cubeToMinBlock(cube.getY()),
                        hasSkyLight);
                cube.setStorageFromSave(storage);
            }
        }

        // 2. Block IDs and metadata
        for (int i = 0; i < cubes.size(); i++) {
            if ((flags[i] & 1) == 0) {
                Cube cube = cubes.get(i);
                // Delegate variable palette/long-array consumption to the same vanilla decoder.
                (cube == null ? new BlockStateContainer() : cube.getStorage().getData()).read(in);
            }
        }

        // 3. block light
        for (int i = 0; i < cubes.size(); i++) {
            if ((flags[i] & 2) != 0) {
                Cube cube = cubes.get(i);
                if (cube == null) in.skipBytes(Cube.SIZE * Cube.SIZE * Cube.SIZE / 2);
                else in.readBytes(cube.getStorage().getBlockLight().getData());
            }
        }

        // 4. sky light
        for (int i = 0; i < cubes.size(); i++) {
            if ((flags[i] & 2) != 0 && hasSkyLight) {
                Cube cube = cubes.get(i);
                if (cube == null) in.skipBytes(Cube.SIZE * Cube.SIZE * Cube.SIZE / 2);
                else in.readBytes(cube.getStorage().getSkyLight().getData());
            }
        }

        int[] oldHeights = null;
        // 5. heightmaps and after all that - update ref counts
        for (int i = 0; i < cubes.size(); i++) {
            if ((flags[i] & 1) == 0) {
                Cube cube = cubes.get(i);
                if (cube == null) {
                    in.skipBytes(Cube.SIZE * Cube.SIZE * Integer.BYTES);
                    continue;
                }
                if (oldHeights == null) oldHeights = new int[Cube.SIZE * Cube.SIZE];
                ILightingManager lm = ((ICubicWorldInternal) cube.getWorld()).getLightingManager();
                IColumnInternal column = cube.getColumn();
                ClientHeightMap coi = (ClientHeightMap) column.getOpacityIndex();
                for (int dx = 0; dx < Cube.SIZE; dx++) {
                    for (int dz = 0; dz < Cube.SIZE; dz++) {
                        oldHeights[AddressTools.getLocalAddress(dx, dz)] = coi.getTopBlockY(dx, dz);
                    }
                }
                column.loadClientHeightmapData(in);
                for (int dx = 0; dx < Cube.SIZE; dx++) {
                    for (int dz = 0; dz < Cube.SIZE; dz++) {
                        int oldY = oldHeights[AddressTools.getLocalAddress(dx, dz)];
                        int newY = coi.getTopBlockY(dx, dz);
                        if (oldY != newY) {
                            lm.updateLightBetween(cube.getColumn(), dx, oldY, newY, dz);
                        }
                    }
                }
                //noinspection ConstantConditions
                cube.getStorage().recalculateRefCounts();
            }
        }
        
        // 6. biomes
        for (int i = 0; i < cubes.size(); i++) {
            if ((flags[i] & 4) == 0)
                continue;
            Cube cube = cubes.get(i);
            if (cube == null) {
                in.skipBytes(Coords.BIOMES_PER_CUBE);
                continue;
            }
            byte[] blockBiomeArray = new byte[Coords.BIOMES_PER_CUBE];
            in.readBytes(blockBiomeArray);
            cube.setBiomeArray(blockBiomeArray);
        }
    }

    static int getEncodedSize(Chunk column) {
        return column.getBiomeArray().length + Cube.SIZE * Cube.SIZE * Integer.BYTES;
    }

    static int getEncodedSize(Collection<Cube> cubes) {
        // 1. isEmpty, hasStorage and hasBiomeArray flags packed in one byte
        int size = cubes.size();

        // 2. block IDs and metadata
        for (Cube cube : cubes) {
            size += getEncodedSize(cube) - 1;
        }
        return size;
    }

    static int getEncodedSize(Cube cube) {
        int size = 1;
        if (!cube.isEmpty()) {
            size += cube.getStorage().getData().getSerializedSize() + Cube.SIZE * Cube.SIZE * Integer.BYTES;
        }
        if (cube.getStorage() != null) {
            size += cube.getStorage().getBlockLight().getData().length;
            if (cube.getWorld().provider.hasSkyLight()) size += cube.getStorage().getSkyLight().getData().length;
        }
        if (cube.getBiomeArray() != null) size += cube.getBiomeArray().length;
        return size;
    }

    static ByteBuf createByteBufForWrite(byte[] data) {
        ByteBuf bytebuf = Unpooled.wrappedBuffer(data);
        bytebuf.writerIndex(0);
        return bytebuf;
    }

    static ByteBuf createByteBufForRead(byte[] data) {
        ByteBuf bytebuf = Unpooled.wrappedBuffer(data);
        bytebuf.readerIndex(0);
        return bytebuf;
    }
}
