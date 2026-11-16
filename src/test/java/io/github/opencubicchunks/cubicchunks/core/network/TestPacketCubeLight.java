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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;

public class TestPacketCubeLight {

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
    }

    private static Cube cube(CubePos pos, ExtendedBlockStorage storage) {
        return new Cube(null, null, null, pos, storage, null, null, null) { };
    }

    private static PacketCubeLight lightOf(int x, int y, int z, byte[] block, byte[] sky) {
        PacketCubeLight packet = new PacketCubeLight();
        packet.x = x;
        packet.y = y;
        packet.z = z;
        packet.blockLight = block;
        packet.skyLight = sky;
        return packet;
    }

    private static byte[] random(long seed) {
        byte[] data = new byte[2048];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    public void roundTripWithAndWithoutSky() {
        for (boolean sky : new boolean[] {true, false}) {
            PacketCubeLight sent = lightOf(-545, -6, 734, random(1), sky ? random(2) : null);
            ByteBuf buffer = Unpooled.buffer();
            sent.toBytes(buffer);
            assertEquals(sent.size(), buffer.readableBytes());
            PacketCubeLight read = new PacketCubeLight();
            read.fromBytes(buffer);
            assertEquals(-545, read.x);
            assertEquals(-6, read.y);
            assertEquals(734, read.z);
            assertArrayEquals(sent.blockLight, read.blockLight);
            if (sky) {
                assertArrayEquals(sent.skyLight, read.skyLight);
            } else {
                assertNull(read.skyLight);
            }
        }
        assertEquals(13 + 4096, lightOf(0, 0, 0, random(3), random(4)).size());
    }

    @Test
    public void appliesToTheCubesStorage() {
        ExtendedBlockStorage storage = new ExtendedBlockStorage(0, true);
        Cube cube = cube(new CubePos(0, 0, 0), storage);
        PacketCubeLight packet = lightOf(0, 0, 0, random(5), random(6));
        assertTrue(packet.applyTo(cube));
        assertArrayEquals(random(5), storage.getBlockLight().getData());
        assertArrayEquals(random(6), storage.getSkyLight().getData());
    }

    @Test
    public void aCubeWithoutStorageTakesNothing() {
        assertFalse(lightOf(0, 0, 0, random(7), null).applyTo(cube(new CubePos(0, 0, 0), null)));
    }

    @Test
    public void theServersCopyIsTaken() {
        ExtendedBlockStorage storage = new ExtendedBlockStorage(0, false);
        storage.getBlockLight().set(1, 2, 3, 9);
        PacketCubeLight packet = lightOf(0, 0, 0, storage.getBlockLight().getData().clone(), null);
        storage.getBlockLight().set(1, 2, 3, 4);
        ExtendedBlockStorage other = new ExtendedBlockStorage(0, false);
        assertTrue(packet.applyTo(cube(new CubePos(0, 0, 0), other)));
        assertEquals(9, other.getBlockLight().get(1, 2, 3));
    }
}
