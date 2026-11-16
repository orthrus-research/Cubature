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

import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Every cube of a held box ({@link PacketHeldBox}) has been sent to the player. It follows the
 * cubes' own messages on the same channel, so a client that reads it has applied them all. It names
 * the box it is about: a client whose box has moved on since ignores it.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class PacketHeldBoxSent extends PacketHeldBox {

    public PacketHeldBoxSent() {
    }

    public PacketHeldBoxSent(int centerX, int centerY, int centerZ, int horizontal, int vertical) {
        super(centerX, centerY, centerZ, horizontal, vertical);
    }

    public static class Handler extends AbstractClientMessageHandler<PacketHeldBoxSent> {

        @Override
        public void handleClientMessage(World world, EntityPlayer player, PacketHeldBoxSent message, MessageContext ctx) {
            io.github.opencubicchunks.cubicchunks.core.client.source.ClientCubeSource.INSTANCE.heldBoxSent(world,
                    message.centerX, message.centerY, message.centerZ, message.horizontal, message.vertical);
        }
    }
}
