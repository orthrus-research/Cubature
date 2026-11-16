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

import io.netty.buffer.ByteBuf;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * The box of cubes the server keeps sent to a player: the cube it centers the player's view on and
 * the horizontal and vertical view distances it applies, in cubes. Sent when the player joins a
 * cubic world, when the center moves to another cube and when the distances change.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class PacketHeldBox implements IMessage {

    int centerX;
    int centerY;
    int centerZ;
    int horizontal;
    int vertical;

    public PacketHeldBox() {
    }

    public PacketHeldBox(int centerX, int centerY, int centerZ, int horizontal, int vertical) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    @Override
    public void fromBytes(ByteBuf in) {
        centerX = in.readInt();
        centerY = in.readInt();
        centerZ = in.readInt();
        horizontal = in.readUnsignedByte();
        vertical = in.readUnsignedByte();
    }

    @Override
    public void toBytes(ByteBuf out) {
        out.writeInt(centerX);
        out.writeInt(centerY);
        out.writeInt(centerZ);
        out.writeByte(horizontal);
        out.writeByte(vertical);
    }

    public static class Handler extends AbstractClientMessageHandler<PacketHeldBox> {

        @Override
        public void handleClientMessage(World world, EntityPlayer player, PacketHeldBox message, MessageContext ctx) {
            io.github.opencubicchunks.cubicchunks.core.client.source.ClientCubeSource.INSTANCE.heldBox(world,
                    message.centerX, message.centerY, message.centerZ, message.horizontal, message.vertical);
        }
    }
}
