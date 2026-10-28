package io.github.opencubicchunks.cubicchunks.core.world;

import io.github.opencubicchunks.cubicchunks.core.lighting.LightingManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class TestClientHeightMap {
    static ClientHeightMap flat(int top) {
        int[] heights = new int[256];
        Arrays.fill(heights, top + 1);
        return new ClientHeightMap(null, heights);
    }

    @Test public void serializationKeepsSignedBigEndianHeightsAndOrder() throws Exception {
        ClientHeightMap map = flat(0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream expected = new DataOutputStream(bytes);
        Random random = new Random(261214);
        for (int i = 0; i < 256; i++) {
            int height = i == 0 ? Integer.MIN_VALUE : i == 255 ? Integer.MAX_VALUE : random.nextInt();
            map.setHeight(i & 15, i >>> 4, height);
            expected.writeInt(height);
        }
        assertArrayEquals(bytes.toByteArray(), map.getData());
        assertNotSame(map.getData(), map.getData());
    }

    @Test public void directUpdatesInvalidateTheMinimumInBothDirections() {
        ClientHeightMap map = flat(63);
        assertEquals(63, map.getLowestTopBlockY());
        map.setHeight(8, 8, -128);
        assertEquals(-128, map.getLowestTopBlockY());
        map.setHeight(8, 8, 384);
        assertEquals(63, map.getLowestTopBlockY());
    }

    @Test public void receivedDataInvalidatesMinimumAndConsumesOnlyItsPayload() {
        ClientHeightMap map = flat(63), incoming = flat(383);
        incoming.setHeight(2, 5, -128);
        assertEquals(63, map.getLowestTopBlockY());
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer(1028));
        try {
            buffer.writeBytes(incoming.getData()); buffer.writeInt(0x12345678);
            map.loadData(buffer);
            assertEquals(-128, map.getLowestTopBlockY());
            assertArrayEquals(incoming.getData(), map.getData());
            assertEquals(0x12345678, buffer.readInt());
        } finally { buffer.release(); }
    }

    @Test public void opaqueUpdatesOnlyRaiseTheSurface() {
        ClientHeightMap map = flat(-128);
        map.onOpacityChange(8, 376, 8, 255);
        map.onOpacityChange(8, 64, 8, 255);
        map.onOpacityChange(8, 64, 8, 0);
        assertEquals(376, map.getTopBlockY(8, 8));
    }

    @Test public void transparentSurfaceScanStopsAtTheFirstOpaqueBlockOrDepthLimit() {
        OpacityColumn column = new OpacityColumn();
        ClientHeightMap map = new ClientHeightMap(column, new int[256]);
        column.opaqueY = -12;
        map.setHeight(8, 8, 0); map.onOpacityChange(8, 0, 8, 0);
        assertEquals(-12, map.getTopBlockY(8, 8));
        column.opaqueY = Integer.MIN_VALUE; column.reads = 0;
        map.setHeight(8, 8, 0); map.onOpacityChange(8, 0, 8, 0);
        assertEquals(-LightingManager.MAX_CLIENT_LIGHT_SCAN_DEPTH, map.getTopBlockY(8, 8));
        assertEquals(LightingManager.MAX_CLIENT_LIGHT_SCAN_DEPTH, column.reads);
    }

    public static final class OpacityColumn extends Chunk {
        public int opaqueY, reads;
        public OpacityColumn() { super(null, 0, 0); }
        @Override public int getBlockLightOpacity(BlockPos pos) {
            reads++;
            return pos.getY() == opaqueY ? 255 : 0;
        }
    }
}
