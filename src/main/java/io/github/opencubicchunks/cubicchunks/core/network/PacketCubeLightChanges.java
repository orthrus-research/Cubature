/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
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
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.client.CubeProviderClient;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.netty.buffer.ByteBuf;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * The light of the blocks of a cube whose light the server changed after the player got the cube:
 * for each, its index ({@code y << 8 | z << 4 | x}) and its block and sky light, 3 bytes a block.
 * The server sends it in place of the whole light ({@link PacketCubeLight}) when few blocks
 * changed; the values are the cube's as they stand when it is sent.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class PacketCubeLightChanges implements IMessage {

    /** Above this many blocks the whole light is as small or smaller: 13 + 3 n against 4,109 bytes. */
    public static final int MOST = (PacketCubeLight.NIBBLES * 2 + 13 - 15) / 3;

    int x;
    int y;
    int z;
    short[] indices;
    /** Block light in the high four bits, sky light in the low four. */
    byte[] values;

    public PacketCubeLightChanges() {
    }

    /** The current light of the given blocks of a cube that has block storage. */
    public PacketCubeLightChanges(Cube cube, short[] changed, int count) {
        ExtendedBlockStorage storage = cube.getStorage();
        if (storage == null) {
            throw new IllegalArgumentException("A cube without storage has no light of its own to send");
        }
        x = cube.getX();
        y = cube.getY();
        z = cube.getZ();
        indices = java.util.Arrays.copyOf(changed, count);
        values = new byte[count];
        NibbleArray sky = cube.getWorld().provider.hasSkyLight() ? storage.getSkyLight() : null;
        for (int i = 0; i < count; i++) {
            int index = indices[i];
            int bx = index & 15;
            int by = index >> 8 & 15;
            int bz = index >> 4 & 15;
            int block = storage.getBlockLight(bx, by, bz);
            int skyLight = sky == null ? 0 : sky.get(bx, by, bz);
            values[i] = (byte) (block << 4 | skyLight);
        }
    }

    @Override
    public void fromBytes(ByteBuf in) {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        int count = in.readUnsignedShort();
        indices = new short[count];
        values = new byte[count];
        for (int i = 0; i < count; i++) {
            indices[i] = (short) (in.readShort() & 0xFFF);
            values[i] = in.readByte();
        }
    }

    @Override
    public void toBytes(ByteBuf out) {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeShort(indices.length);
        for (int i = 0; i < indices.length; i++) {
            out.writeShort(indices[i]);
            out.writeByte(values[i]);
        }
    }

    /** The bytes on the wire, for the server's count. */
    public int size() {
        return 14 + 3 * indices.length;
    }

    /**
     * Writes the light into a cube's storage.
     *
     * @return the box of the blocks written, {minX, minY, minZ, maxX, maxY, maxZ}, or null when the
     *         cube has no storage or nothing was written
     */
    @javax.annotation.Nullable
    int[] applyTo(Cube cube) {
        ExtendedBlockStorage storage = cube.getStorage();
        if (storage == null || indices.length == 0) {
            return null;
        }
        NibbleArray sky = storage.getSkyLight();
        int[] box = {15, 15, 15, 0, 0, 0};
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            int bx = index & 15;
            int by = index >> 8 & 15;
            int bz = index >> 4 & 15;
            storage.getBlockLight().set(bx, by, bz, values[i] >> 4 & 15);
            if (sky != null) {
                sky.set(bx, by, bz, values[i] & 15);
            }
            box[0] = Math.min(box[0], bx);
            box[1] = Math.min(box[1], by);
            box[2] = Math.min(box[2], bz);
            box[3] = Math.max(box[3], bx);
            box[4] = Math.max(box[4], by);
            box[5] = Math.max(box[5], bz);
        }
        return box;
    }

    public static class Handler extends AbstractClientMessageHandler<PacketCubeLightChanges> {

        @Override
        public void handleClientMessage(World world, EntityPlayer player, PacketCubeLightChanges message,
                MessageContext ctx) {
            if (!((ICubicWorld) world).isCubicWorld()) {
                return;
            }
            CubeProviderClient cubes = (CubeProviderClient) ((ICubicWorld) world).getCubeCache();
            Cube cube = cubes.getLoadedCube(new CubePos(message.x, message.y, message.z));
            if (cube == null) {
                return;
            }
            int[] box = message.applyTo(cube);
            if (box != null) {
                io.github.opencubicchunks.cubicchunks.core.client.source.ClientCubeSource.INSTANCE.lightChanged(cube,
                        box[0], box[1], box[2], box[3], box[4], box[5]);
            }
        }
    }
}
