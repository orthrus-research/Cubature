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

public class TestPacketCubeLightChanges {

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
    }

    private static Cube cube(ExtendedBlockStorage storage) {
        return new Cube(null, null, null, new CubePos(3, -2, 9), storage, null, null, null) { };
    }

    private static PacketCubeLightChanges of(short[] indices, byte[] values) {
        PacketCubeLightChanges packet = new PacketCubeLightChanges();
        packet.x = 3;
        packet.y = -2;
        packet.z = 9;
        packet.indices = indices;
        packet.values = values;
        return packet;
    }

    @Test
    public void roundTripAndSize() {
        PacketCubeLightChanges sent = of(new short[] {0, 273, 4095}, new byte[] {(byte) 0xF0, 0x3C, (byte) 0x0F});
        ByteBuf buffer = Unpooled.buffer();
        sent.toBytes(buffer);
        assertEquals(sent.size(), buffer.readableBytes());
        assertEquals(14 + 9, sent.size());
        PacketCubeLightChanges read = new PacketCubeLightChanges();
        read.fromBytes(buffer);
        assertEquals(3, read.x);
        assertEquals(-2, read.y);
        assertEquals(9, read.z);
        assertArrayEquals(sent.indices, read.indices);
        assertArrayEquals(sent.values, read.values);
    }

    @Test
    public void appliesOnlyTheChangedBlocksAndGivesTheirBox() {
        ExtendedBlockStorage storage = new ExtendedBlockStorage(0, true);
        storage.getBlockLight().set(0, 0, 0, 5);
        storage.getSkyLight().set(0, 0, 0, 6);
        // index y << 8 | z << 4 | x: (1, 2, 3) is 2 << 8 | 3 << 4 | 1 = 561; (14, 9, 4) is 9 << 8 | 4 << 4 | 14.
        short a = (short) (2 << 8 | 3 << 4 | 1);
        short b = (short) (9 << 8 | 4 << 4 | 14);
        int[] box = of(new short[] {a, b}, new byte[] {(byte) 0x7A, 0x2F}).applyTo(cube(storage));
        assertArrayEquals(new int[] {1, 2, 3, 14, 9, 4}, box);
        assertEquals(7, storage.getBlockLight().get(1, 2, 3));
        assertEquals(10, storage.getSkyLight().get(1, 2, 3));
        assertEquals(2, storage.getBlockLight().get(14, 9, 4));
        assertEquals(15, storage.getSkyLight().get(14, 9, 4));
        // Untouched blocks keep their light.
        assertEquals(5, storage.getBlockLight().get(0, 0, 0));
        assertEquals(6, storage.getSkyLight().get(0, 0, 0));
    }

    @Test
    public void nothingToApplyGivesNoBox() {
        assertNull(of(new short[0], new byte[0]).applyTo(cube(new ExtendedBlockStorage(0, true))));
        assertNull(of(new short[] {1}, new byte[] {1}).applyTo(cube(null)));
    }

    @Test
    public void theChangesAndTheWholeLightLeaveTheSameCube() {
        Random random = new Random(11);
        ExtendedBlockStorage server = new ExtendedBlockStorage(0, true);
        ExtendedBlockStorage client = new ExtendedBlockStorage(0, true);
        for (int i = 0; i < 4096; i++) {
            int v = random.nextInt(16);
            server.getBlockLight().set(i & 15, i >> 8, i >> 4 & 15, v);
            client.getBlockLight().set(i & 15, i >> 8, i >> 4 & 15, v);
            server.getSkyLight().set(i & 15, i >> 8, i >> 4 & 15, 15 - v);
            client.getSkyLight().set(i & 15, i >> 8, i >> 4 & 15, 15 - v);
        }
        // The server changes 200 blocks; the client gets only those.
        short[] changed = new short[200];
        byte[] values = new byte[200];
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int n = 0; n < 200; n++) {
            int i;
            do {
                i = random.nextInt(4096);
            } while (!used.add(i));
            int block = random.nextInt(16);
            int sky = random.nextInt(16);
            server.getBlockLight().set(i & 15, i >> 8, i >> 4 & 15, block);
            server.getSkyLight().set(i & 15, i >> 8, i >> 4 & 15, sky);
            changed[n] = (short) i;
            values[n] = (byte) (block << 4 | sky);
        }
        assertTrue(of(changed, values).applyTo(cube(client)) != null);
        assertArrayEquals(server.getBlockLight().getData(), client.getBlockLight().getData());
        assertArrayEquals(server.getSkyLight().getData(), client.getSkyLight().getData());
    }

    @Test
    public void theMostChangesStillCostLessThanTheWholeLight() {
        assertTrue(14 + 3 * PacketCubeLightChanges.MOST < 13 + 4096);
        assertTrue(14 + 3 * (PacketCubeLightChanges.MOST + 2) >= 13 + 4096);
    }
}
