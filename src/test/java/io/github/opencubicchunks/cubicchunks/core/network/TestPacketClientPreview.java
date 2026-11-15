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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ClientPreviewInputs;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ClientPreviewOffer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreviewFactory;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewFactories;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class TestPacketClientPreview {

    private static PacketClientPreview roundTrip(PacketClientPreview sent) {
        ByteBuf wire = Unpooled.buffer();
        try {
            sent.toBytes(wire);
            PacketClientPreview received = new PacketClientPreview();
            received.fromBytes(wire);
            assertFalse(wire.isReadable());
            return received;
        } finally {
            wire.release();
        }
    }

    @Test public void anOfferArrivesWhole() {
        byte[] definitions = "{\"schema\":1,\"roles\":{\"wood.oak.log\":[\"minecraft:log\"]}}"
                .getBytes(StandardCharsets.UTF_8);
        ClientPreviewOffer offer =
                new ClientPreviewOffer("riven", "0a1b2c", -8675309123456789L, "scale=4", definitions);
        PacketClientPreview received = roundTrip(new PacketClientPreview(-1, offer));
        assertEquals(-1, received.dimension());
        ClientPreviewOffer got = received.offer();
        assertNotNull(got);
        assertEquals("riven", got.factory());
        assertEquals("0a1b2c", got.identity());
        assertEquals(-8675309123456789L, got.seed());
        assertEquals("scale=4", got.generatorOptions());
        assertArrayEquals(definitions, got.recordedDefinitions());

        // What a factory is handed is exactly what the server offered.
        ClientPreviewInputs inputs = new ClientPreviewInputs(received.dimension(), got, null, null);
        assertEquals(-1, inputs.dimension());
        assertEquals("0a1b2c", inputs.identity());
        assertEquals(-8675309123456789L, inputs.seed());
        assertEquals("scale=4", inputs.generatorOptions());
        assertArrayEquals(definitions, inputs.recordedDefinitions());
        assertNull(inputs.buildPool());
        assertNull(inputs.derivedDataDirectory());
    }

    @Test public void aWithheldSeedSendsNothingASeedCouldBeCheckedAgainst() {
        ByteBuf wire = Unpooled.buffer();
        try {
            new PacketClientPreview(7, null).toBytes(wire);
            // The dimension and one flag: no identity, no options, no definitions.
            assertEquals(5, wire.readableBytes());
            PacketClientPreview received = new PacketClientPreview();
            received.fromBytes(wire);
            assertEquals(7, received.dimension());
            assertNull(received.offer());
        } finally {
            wire.release();
        }
    }

    @Test public void definitionsLongerThanTheMessageOrTheLimitAreRefused() {
        try {
            new ClientPreviewOffer("riven", "id", 1, "", new byte[ClientPreviewOffer.MAXIMUM_DEFINITIONS_BYTES + 1]);
            fail("An offer took more definitions than the limit");
        } catch (IllegalArgumentException expected) {
            // The server's generator learns at once.
        }
        ByteBuf wire = Unpooled.buffer();
        try {
            new PacketClientPreview(0, new ClientPreviewOffer("riven", "id", 1, "", new byte[8])).toBytes(wire);
            // Claim more definition bytes than the message holds.
            wire.setInt(wire.writerIndex() - 8 - 4, 1 << 19);
            try {
                new PacketClientPreview().fromBytes(wire);
                fail("A message claiming more bytes than it holds was read");
            } catch (IllegalArgumentException expected) {
                // No allocation for a length the message cannot back.
            }
        } finally {
            wire.release();
        }
    }

    @Test public void aFactoryNameIsRegisteredOnce() {
        ICubicTerrainPreviewFactory declines = inputs -> null;
        String name = "test-" + System.nanoTime();
        assertNull(TerrainPreviewFactories.get(name));
        TerrainPreviewFactories.register(name, declines);
        assertEquals(declines, TerrainPreviewFactories.get(name));
        try {
            TerrainPreviewFactories.register(name, inputs -> null);
            fail("A factory name was registered twice");
        } catch (IllegalStateException expected) {
            // The first registration stands.
        }
        assertEquals(declines, TerrainPreviewFactories.get(name));
    }
}
