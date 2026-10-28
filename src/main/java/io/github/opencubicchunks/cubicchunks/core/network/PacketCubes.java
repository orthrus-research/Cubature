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

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.client.CubeProviderClient;
import io.github.opencubicchunks.cubicchunks.core.util.PacketUtils;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class PacketCubes implements IMessage {

    private CubePos[] cubePos;
    private byte[] data;
    private List<List<NBTTagCompound>> tileEntityTags;
    private byte[] encodedTileEntityTags;
    private volatile int serializedSize = -1;
    private long queuedNanos;

    public PacketCubes() {
    }

    public PacketCubes(List<Cube> cubes) {
        CubeSendDiagnostics.Observer observer = CubeSendDiagnostics.getObserver();
        long started = observer == null ? 0 : System.nanoTime();
        cubes.sort(Comparator.<Cube>comparingInt(c -> c.getCoords().getY())
                .thenComparingInt(c -> c.getCoords().getX())
                .thenComparingInt(c -> c.getCoords().getZ()));
        this.cubePos = new CubePos[cubes.size()];
        int coordinateBytes = 0;
        for (int i = 0; i < cubes.size(); i++) {
            cubePos[i] = cubes.get(i).getCoords();
            coordinateBytes += getCoordinateSize(cubePos[i]);
        }
        this.data = new byte[WorldEncoder.getEncodedSize(cubes)];
        PacketBuffer out = new PacketBuffer(WorldEncoder.createByteBufForWrite(this.data));

        try {
            WorldEncoder.encodeCubes(out, cubes);
            if (out.writerIndex() != this.data.length) throw new IllegalStateException("Incorrect cube packet size");
        } finally {
            out.release();
        }
        boolean hasTiles = false;
        for (Cube cube : cubes) if (!cube.getTileEntityMap().isEmpty()) { hasTiles = true; break; }
        if (!hasTiles) {
            encodedTileEntityTags = new byte[cubes.size() * 4]; // One zero count per cube, without a temporary buffer/list.
        } else if (observer != null || CubicChunksConfig.maxCubeSendBytesPerTick > 0) {
            // Exact byte budgeting snapshots and encodes once on the owner, without re-running TE hooks.
            ByteBuf tags = Unpooled.buffer();
            try {
                for (Cube cube : cubes) {
                    writeTileEntityTags(tags, cube.getTileEntityMap().values());
                }
                this.encodedTileEntityTags = new byte[tags.readableBytes()];
                tags.readBytes(this.encodedTileEntityTags);
            } finally {
                tags.release();
            }
        } else {
            // Ordinary unbudgeted sends preserve Netty's NBT encoding work instead of moving it onto the tick thread.
            tileEntityTags = new ArrayList<>(cubes.size());
            for (Cube cube : cubes) {
                if (cube.getTileEntityMap().isEmpty()) tileEntityTags.add(Collections.emptyList());
                else {
                    List<NBTTagCompound> tags = new ArrayList<>(cube.getTileEntityMap().size());
                    for (TileEntity tile : cube.getTileEntityMap().values()) tags.add(tile.getUpdateTag());
                    tileEntityTags.add(tags);
                }
            }
        }
        if (encodedTileEntityTags != null) serializedSize = 2 + coordinateBytes + 4 + data.length + encodedTileEntityTags.length;
        if (observer != null) observer.onSnapshot(this, started, System.nanoTime());
    }

    static void writeTileEntityTags(ByteBuf out, Collection<TileEntity> tiles) {
        out.writeInt(tiles.size());
        for (TileEntity tile : tiles) ByteBufUtils.writeTag(out, tile.getUpdateTag());
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int start = buf.readerIndex();
        int cubeCount = buf.readUnsignedShort();
        cubePos = new CubePos[cubeCount];
        for (int i = 0; i < this.cubePos.length; i++) {
            cubePos[i] = PacketUtils.readCubePos(buf);
        }

        int dataLength = buf.readInt();
        if (dataLength < 0 || dataLength > buf.readableBytes()) throw new IllegalArgumentException("Invalid cube packet data length");
        this.data = new byte[dataLength];
        buf.readBytes(this.data);

        this.tileEntityTags = new ArrayList<>(cubeCount);
        for (int i = 0; i < cubeCount; i++) {
            int numTiles = buf.readInt();
            if (numTiles < 0 || numTiles > buf.readableBytes()) throw new IllegalArgumentException("Invalid cube tile entity count");
            List<NBTTagCompound> tags = numTiles == 0 ? Collections.emptyList() : new ArrayList<>(numTiles);
            for (int j = 0; j < numTiles; j++) {
                tags.add(ByteBufUtils.readTag(buf));
            }
            this.tileEntityTags.add(tags);
        }
        serializedSize = buf.readerIndex() - start;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int start = buf.writerIndex();
        buf.writeShort(cubePos.length);
        for (CubePos pos : cubePos) {
            PacketUtils.write(buf, pos);
        }

        buf.writeInt(this.data.length);
        buf.writeBytes(this.data);

        if (encodedTileEntityTags != null) {
            buf.writeBytes(encodedTileEntityTags);
        } else {
            this.tileEntityTags.forEach(tags -> {
                buf.writeInt(tags.size());
                tags.forEach(tag -> ByteBufUtils.writeTag(buf, tag));
            });
        }
        serializedSize = buf.writerIndex() - start;
    }

    public int getCubeCount() { return cubePos.length; }
    /** Exact contribution for a cube without TEs, before any pending lighting is processed. */
    public static int getUncompressedDataSize(Cube cube) { return getCoordinateSize(cube.getCoords()) + 4 + WorldEncoder.getEncodedSize(cube); }
    static int getCoordinateSize(CubePos pos) {
        return signedVarIntSize(pos.getX()) + signedVarIntSize(pos.getY()) + signedVarIntSize(pos.getZ());
    }

    private static int signedVarIntSize(int value) {
        int remaining = (value < 0 ? ~value : value) >>> 6;
        int bytes = 1;
        while (remaining != 0) { remaining >>>= 7; bytes++; }
        return bytes;
    }
    /** Uncompressed body including TE NBT; -1 until encoding for ordinary unbudgeted TE packets. Not wire framing/compression. */
    public int getSerializedSize() { return serializedSize; }

    /** Coordinate-only correlation across JVMs; this is not a content digest or a unique packet ID. */
    public long getCoordinateFingerprint() {
        long hash = 0xcbf29ce484222325L;
        for (CubePos pos : cubePos) {
            hash = (hash ^ pos.getX()) * 0x100000001b3L;
            hash = (hash ^ pos.getY()) * 0x100000001b3L;
            hash = (hash ^ pos.getZ()) * 0x100000001b3L;
        }
        return hash;
    }

    void onClientQueued() {
        CubeSendDiagnostics.Observer observer = CubeSendDiagnostics.getObserver();
        if (observer != null) {
            queuedNanos = System.nanoTime();
            observer.onQueued(this, queuedNanos);
        }
    }

    CubePos[] getCubePos() {
        return cubePos;
    }

    byte[] getData() {
        return data;
    }

    List<List<NBTTagCompound>> getTileEntityTags() {
        return this.tileEntityTags;
    }

    public static class Handler extends AbstractClientMessageHandler<PacketCubes> {

        @Override
        public void handleClientMessage(World world, EntityPlayer player, PacketCubes message, MessageContext ctx) {
            CubeSendDiagnostics.Observer observer = CubeSendDiagnostics.getObserver();
            long started = observer == null ? 0 : System.nanoTime();
            boolean completed = false;
            try {
                apply(world, player, message);
                completed = true;
            } finally {
                if (observer != null) observer.onApply(message, message.queuedNanos, started, System.nanoTime(), completed);
            }
        }

        private void apply(World world, EntityPlayer player, PacketCubes message) {
            WorldClient worldClient = (WorldClient) player.getEntityWorld();
            CubeProviderClient cubeCache = (CubeProviderClient) worldClient.getChunkProvider();

            CubePos[] cubePos = message.getCubePos();
            List<Cube> cubes = new ArrayList<>(cubePos.length);
            for (CubePos pos : cubePos) {
                Cube cube = cubeCache.loadCube(pos); // new cube
                //isEmpty actually checks if the column is a BlankColumn
                if (cube == null) {
                    CubicChunks.LOGGER.error("Out of order cube received! No column for cube at {} exists!", pos);
                }
                cubes.add(cube);
            }


            byte[] data = message.getData();
            ByteBuf buf = WorldEncoder.createByteBufForRead(data);
            try {
                WorldEncoder.decodeCube(new PacketBuffer(buf), cubes, world.provider.hasSkyLight());
            } finally {
                buf.release();
            }

            cubes.stream().filter(Objects::nonNull).forEach(cube -> {
                io.github.opencubicchunks.cubicchunks.core.compat.celeritas.CeleritasCompat.cubeLoaded(cube);
                cube.markForRenderUpdate();
            });
            for (Cube cube:cubes) if(cube!=null)
                io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat.columnLoaded(cube.getColumn());

            for (int i = 0; i < cubes.size(); i++) {
                if (cubes.get(i) == null) continue;
                for (NBTTagCompound tag : message.getTileEntityTags().get(i)) {
                    int blockX = tag.getInteger("x");
                    int blockY = tag.getInteger("y");
                    int blockZ = tag.getInteger("z");
                    BlockPos pos = new BlockPos(blockX, blockY, blockZ);
                    TileEntity tileEntity = worldClient.getTileEntity(pos);

                    if (tileEntity != null) {
                        tileEntity.handleUpdateTag(tag);
                    }
                }
            }
        }
    }
}
