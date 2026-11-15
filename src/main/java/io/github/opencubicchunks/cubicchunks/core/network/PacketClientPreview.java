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

import io.github.opencubicchunks.cubicchunks.api.worldgen.ClientPreviewOffer;
import io.netty.buffer.ByteBuf;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Sent after {@link PacketCubicWorldData} to a player joining a cubic dimension whose generator
 * offers its clients a terrain preview (docs/CUBE-SOURCE.md, section 8): what a client needs to
 * build that preview itself. When the server withholds its seed the message says only that, and
 * carries nothing a seed could be checked against.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class PacketClientPreview implements IMessage {

    private static final int MAXIMUM_TEXT = 1024;

    private int dimension;
    @Nullable private ClientPreviewOffer offer;

    public PacketClientPreview() {
    }

    /** An offer for a dimension, or with a null offer the notice that the seed is withheld. */
    public PacketClientPreview(int dimension, @Nullable ClientPreviewOffer offer) {
        this.dimension = dimension;
        this.offer = offer;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimension = buf.readInt();
        if (!buf.readBoolean()) {
            offer = null;
            return;
        }
        String factory = text(buf);
        String identity = text(buf);
        long seed = buf.readLong();
        String options = text(buf);
        int length = buf.readInt();
        if (length < 0 || length > ClientPreviewOffer.MAXIMUM_DEFINITIONS_BYTES || length > buf.readableBytes()) {
            throw new IllegalArgumentException("Client preview definitions of " + length + " bytes");
        }
        byte[] definitions = new byte[length];
        buf.readBytes(definitions);
        offer = new ClientPreviewOffer(factory, identity, seed, options, definitions);
    }

    private static String text(ByteBuf buf) {
        String text = ByteBufUtils.readUTF8String(buf);
        if (text.length() > MAXIMUM_TEXT) {
            throw new IllegalArgumentException("Client preview text of " + text.length() + " characters");
        }
        return text;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeBoolean(offer != null);
        if (offer == null) {
            return;
        }
        ByteBufUtils.writeUTF8String(buf, offer.factory());
        ByteBufUtils.writeUTF8String(buf, offer.identity());
        buf.writeLong(offer.seed());
        ByteBufUtils.writeUTF8String(buf, offer.generatorOptions());
        byte[] definitions = offer.recordedDefinitions();
        buf.writeInt(definitions.length);
        buf.writeBytes(definitions);
    }

    public int dimension() {
        return dimension;
    }

    /** The server's offer, or null when it withholds its seed. */
    @Nullable
    public ClientPreviewOffer offer() {
        return offer;
    }

    public static class Handler extends AbstractClientMessageHandler<PacketClientPreview> {

        @Override
        public void handleClientMessage(World world, EntityPlayer player, PacketClientPreview message,
                MessageContext ctx) {
            io.github.opencubicchunks.cubicchunks.core.client.source.ClientTerrainPreviews.INSTANCE
                    .offered(world, message.dimension(), message.offer());
        }
    }
}
