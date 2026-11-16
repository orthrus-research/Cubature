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
import static org.junit.Assert.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.util.ResourceLeakDetector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestWorldEncoderBuffers {

    private ResourceLeakDetector.Level before;

    @Before public void paranoid() {
        before = ResourceLeakDetector.getLevel();
        // Every buffer tracked: an unreleased one would be reported once collected.
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    @After public void restore() {
        ResourceLeakDetector.setLevel(before);
    }

    @Test
    public void aColumnsBytesAreWrittenAndReadAndBothBuffersReleased() {
        byte[] data = new byte[256 + 8];
        ByteBuf written = WorldEncoder.writeInto(data, out -> {
            for (int i = 0; i < 256; i++) {
                out.writeByte(i);
            }
            out.writeLong(0x0123456789ABCDEFL);
        });
        assertEquals(0, written.refCnt());
        byte[] biomes = new byte[256];
        long[] heights = new long[1];
        ByteBuf read = WorldEncoder.readFrom(data, in -> {
            in.readBytes(biomes);
            heights[0] = in.readLong();
        });
        assertEquals(0, read.refCnt());
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) i, biomes[i]);
        }
        assertEquals(0x0123456789ABCDEFL, heights[0]);
    }

    @Test
    public void aWriterOrReaderThatThrowsStillReleasesItsBuffer() {
        ByteBuf[] seen = new ByteBuf[2];
        try {
            WorldEncoder.writeInto(new byte[4], out -> {
                seen[0] = out;
                throw new IllegalStateException("encode");
            });
        } catch (IllegalStateException expected) {
            assertEquals("encode", expected.getMessage());
        }
        try {
            WorldEncoder.readFrom(new byte[4], in -> {
                seen[1] = in;
                throw new IllegalStateException("decode");
            });
        } catch (IllegalStateException expected) {
            assertEquals("decode", expected.getMessage());
        }
        assertEquals(0, seen[0].refCnt());
        assertEquals(0, seen[1].refCnt());
    }

    @Test
    public void theBufferWritesIntoTheArrayItWraps() {
        byte[] data = new byte[3];
        WorldEncoder.writeInto(data, out -> out.writeBytes(new byte[] {7, 8, 9}));
        assertArrayEquals(new byte[] {7, 8, 9}, data);
        assertTrue(ResourceLeakDetector.isEnabled());
    }
}
