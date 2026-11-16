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

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * The whole light of a cube the player already has: its block light and, in a dimension with sky
 * light, its sky light, 2,048 bytes each. The server sends it when it changes the light of a cube it
 * has already sent and no block changed with it, so the client's light follows the server's.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class PacketCubeLight implements IMessage {

    static final int NIBBLES = 2048;

    int x;
    int y;
    int z;
    byte[] blockLight;
    @Nullable byte[] skyLight;

    public PacketCubeLight() {
    }

    /** The light of a cube that has block storage. */
    public PacketCubeLight(Cube cube) {
        ExtendedBlockStorage storage = cube.getStorage();
        if (storage == null) {
            throw new IllegalArgumentException("A cube without storage has no light of its own to send");
        }
        x = cube.getX();
        y = cube.getY();
        z = cube.getZ();
        blockLight = storage.getBlockLight().getData().clone();
        NibbleArray sky = storage.getSkyLight();
        skyLight = cube.getWorld().provider.hasSkyLight() && sky != null ? sky.getData().clone() : null;
    }

    @Override
    public void fromBytes(ByteBuf in) {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        blockLight = new byte[NIBBLES];
        in.readBytes(blockLight);
        if (in.readBoolean()) {
            skyLight = new byte[NIBBLES];
            in.readBytes(skyLight);
        }
    }

    @Override
    public void toBytes(ByteBuf out) {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeBytes(blockLight);
        out.writeBoolean(skyLight != null);
        if (skyLight != null) {
            out.writeBytes(skyLight);
        }
    }

    /** The bytes on the wire, for the server's count. */
    public int size() {
        return 13 + NIBBLES + (skyLight == null ? 0 : NIBBLES);
    }

    /** Writes the light into a cube's storage; false when the cube has none to write into. */
    boolean applyTo(Cube cube) {
        ExtendedBlockStorage storage = cube.getStorage();
        if (storage == null) {
            return false;
        }
        System.arraycopy(blockLight, 0, storage.getBlockLight().getData(), 0, NIBBLES);
        NibbleArray sky = storage.getSkyLight();
        if (skyLight != null && sky != null) {
            System.arraycopy(skyLight, 0, sky.getData(), 0, NIBBLES);
        }
        return true;
    }

    public static class Handler extends AbstractClientMessageHandler<PacketCubeLight> {

        @Override
        public void handleClientMessage(World world, EntityPlayer player, PacketCubeLight message, MessageContext ctx) {
            if (!((ICubicWorld) world).isCubicWorld()) {
                return;
            }
            CubeProviderClient cubes = (CubeProviderClient) ((ICubicWorld) world).getCubeCache();
            Cube cube = cubes.getLoadedCube(new CubePos(message.x, message.y, message.z));
            if (cube == null || !message.applyTo(cube)) {
                return;
            }
            io.github.opencubicchunks.cubicchunks.core.client.source.ClientCubeSource.INSTANCE.lightReplaced(cube);
        }
    }
}
