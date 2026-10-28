package io.github.opencubicchunks.cubicchunks.core.network;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class TestWorldEncoderMissingCubes {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void unloadedColumnsConsumeAllOriginalPayloadFieldsWithSkyLight() { check(true); }
    @Test public void unloadedColumnsConsumeAllOriginalPayloadFieldsWithoutSkyLight() { check(false); }

    private static void check(boolean sky) {
        // Full nonempty cube, empty storage, no storage, and empty storage with 3D biomes.
        byte[] flags = {6, 3, 1, 7};
        ExtendedBlockStorage storage = new ExtendedBlockStorage(-128, sky);
        storage.set(1, 2, 3, Blocks.STONE.getDefaultState());
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            buf.writeBytes(flags);
            storage.getData().write(buf);
            for (byte f : flags) if ((f & 2) != 0) buf.writeZero(2048);
            if (sky) for (byte f : flags) if ((f & 2) != 0) buf.writeZero(2048);
            buf.writeZero(256 * 4);
            for (byte f : flags) if ((f & 4) != 0) buf.writeZero(Coords.BIOMES_PER_CUBE);
            int payloadEnd = buf.writerIndex();
            buf.writeInt(0x12345678);
            WorldEncoder.decodeCube(buf, Collections.nCopies(flags.length, null), sky);
            assertEquals(payloadEnd, buf.readerIndex());
            assertEquals(0x12345678, buf.readInt());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }
}
