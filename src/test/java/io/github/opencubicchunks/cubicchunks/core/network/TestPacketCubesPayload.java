package io.github.opencubicchunks.cubicchunks.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.util.PacketUtils;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import org.junit.Test;
import org.junit.BeforeClass;

import java.util.Collections;
import static org.junit.Assert.*;

public class TestPacketCubesPayload {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }
    @Test public void bodySizeIncludesCoordinatesHeadersAndTileNbtAndRoundTripsExactly() {
        ByteBuf wire = Unpooled.buffer(), copy = Unpooled.buffer();
        try {
            wire.writeShort(2);
            PacketUtils.write(wire, new CubePos(-12, -8, 40));
            PacketUtils.write(wire, new CubePos(12, 23, -40));
            wire.writeInt(4).writeInt(0x01020304);
            wire.writeInt(1);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", -192); tag.setInteger("y", -128); tag.setInteger("z", 640);
            tag.setString("name", "full tile data");
            tag.setIntArray("inventory", new int[] {0, 127, -200, Integer.MAX_VALUE});
            ByteBufUtils.writeTag(wire, tag);
            wire.writeInt(0);
            byte[] expected = new byte[wire.readableBytes()]; wire.getBytes(wire.readerIndex(), expected);
            PacketCubes packet = new PacketCubes();
            packet.fromBytes(wire);
            assertEquals(2, packet.getCubeCount());
            assertEquals(expected.length, packet.getSerializedSize());
            assertEquals(tag, packet.getTileEntityTags().get(0).get(0));
            packet.toBytes(copy);
            byte[] actual = new byte[copy.readableBytes()]; copy.readBytes(actual);
            assertArrayEquals(expected, actual);
            assertEquals(expected.length, packet.getSerializedSize());
            assertFalse(wire.isReadable());
        } finally { wire.release(); copy.release(); }
    }

    @Test public void coordinateSizeMatchesSignedWireVarintsAtEveryWidthBoundary() {
        int[] values = {Integer.MIN_VALUE, -8193, -8192, -65, -64, -1, 0, 63, 64, 8191, 8192, Integer.MAX_VALUE};
        ByteBuf out = Unpooled.buffer();
        try {
            for (int value : values) {
                CubePos pos = new CubePos(value, ~value, value / 2);
                out.clear(); PacketUtils.write(out, pos);
                assertEquals(out.readableBytes(), PacketCubes.getCoordinateSize(pos));
            }
        } finally { out.release(); }
    }

    @Test public void snapshotSerializesTileHookOnceAndOwnsTheEncodedBytes() {
        ByteBuf out = Unpooled.buffer();
        CountingTile tile = new CountingTile();
        try {
            tile.tag.setInteger("value", 42);
            PacketCubes.writeTileEntityTags(out, Collections.singletonList(tile));
            tile.tag.setInteger("value", 99);
            assertEquals(1, tile.reads);
            assertEquals(1, out.readInt());
            assertEquals(42, ByteBufUtils.readTag(out).getInteger("value"));
            assertFalse(out.isReadable());
        } finally { out.release(); }
    }

    @Test(expected = IllegalArgumentException.class) public void malformedPayloadLengthCannotAllocateBeyondTheFrame() {
        ByteBuf wire = Unpooled.buffer();
        try { wire.writeShort(0).writeInt(Integer.MAX_VALUE); new PacketCubes().fromBytes(wire); }
        finally { wire.release(); }
    }

    private static final class CountingTile extends TileEntity {
        final NBTTagCompound tag = new NBTTagCompound();
        int reads;
        @Override public NBTTagCompound getUpdateTag() { reads++; return tag; }
    }
}
