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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

public class TestPacketHeldBox {

    @Test
    public void roundTrip() {
        PacketHeldBox sent = new PacketHeldBox(-545, -4, 734, 12, 32);
        ByteBuf buffer = Unpooled.buffer();
        sent.toBytes(buffer);
        assertEquals(14, buffer.readableBytes());
        PacketHeldBox read = new PacketHeldBox();
        read.fromBytes(buffer);
        assertEquals(-545, read.centerX);
        assertEquals(-4, read.centerY);
        assertEquals(734, read.centerZ);
        assertEquals(12, read.horizontal);
        assertEquals(32, read.vertical);
    }

    private static ICubeSource holding(int[] box) {
        return new ICubeSource() {
            @Override public void setListener(ICubeSourceListener l) { }
            @Override public boolean attached() { return true; }
            @Override public long worldIdentity() { return 1; }
            @Override public int minCubeY() { return -8; }
            @Override public int maxCubeY() { return 8; }
            @Override public boolean hasSkyLight() { return true; }
            @Override public boolean isLoaded(int x, int y, int z) { return false; }
            @Override public long version(int x, int y, int z) { return -1; }
            @Override public void forEachLoaded(CubeVisitor visitor) { }
            @Override public ICubeSnapshot snapshot(int x, int y, int z) { throw new UnsupportedOperationException(); }
            @Override public ICubicTerrainPreview terrainPreview() { return null; }
            @Override public int[] heldBox() { return box == null ? null : box.clone(); }
        };
    }

    @Test
    public void theBoxIsInclusiveAndSeparateAlongY() {
        ICubeSource source = holding(new int[] {10, 0, -10, 2, 1});
        assertTrue(source.inHeldBox(10, 0, -10));
        assertTrue(source.inHeldBox(12, 1, -8));
        assertTrue(source.inHeldBox(8, -1, -12));
        assertFalse(source.inHeldBox(13, 0, -10));
        assertFalse(source.inHeldBox(10, 2, -10));
        assertFalse(source.inHeldBox(10, 0, -13));
    }

    @Test
    public void noBoxHoldsNothing() {
        assertFalse(holding(null).inHeldBox(0, 0, 0));
    }
}
