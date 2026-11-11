package io.github.opencubicchunks.cubicchunks.core.world.chunkloader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class TestCubicTicketData {

    /** A ticket holding only what the cubic data reads and writes; the Mixin supplies these on Forge's Ticket. */
    private static final class Ticket {
        Map<ChunkPos, IntSet> forced = new HashMap<>();
        Integer entityCubeY;

        ICubicTicketInternal view() {
            return (ICubicTicketInternal) Proxy.newProxyInstance(ICubicTicketInternal.class.getClassLoader(),
                    new Class<?>[] {ICubicTicketInternal.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getAllForcedChunkCubes": return forced;
                            case "setAllForcedChunkCubes": forced = cast(args[0]); return null;
                            case "setEntityChunkY": entityCubeY = (Integer) args[0]; return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }
    }

    @Test public void anEntityTicketKeepsItsEntityCubeWithoutForcedCubes() {
        NBTTagCompound nbt = new NBTTagCompound();
        CubicTicketData.write(nbt, new Ticket().view(), -37);
        assertTrue(nbt.hasKey(CubicTicketData.TAG));
        Ticket loaded = new Ticket();
        CubicTicketData.read(nbt, loaded.view());
        assertEquals(Integer.valueOf(-37), loaded.entityCubeY);
        assertTrue(loaded.forced.isEmpty());
    }

    @Test public void forcedCubesAndTheEntityCubeRoundTrip() {
        Ticket ticket = new Ticket();
        ticket.forced.put(new ChunkPos(-3, 9), new IntArraySet(new int[] {-2, 0, 255}));
        NBTTagCompound nbt = new NBTTagCompound();
        CubicTicketData.write(nbt, ticket.view(), 4);
        Ticket loaded = new Ticket();
        CubicTicketData.read(nbt, loaded.view());
        assertEquals(Integer.valueOf(4), loaded.entityCubeY);
        assertEquals(ticket.forced, loaded.forced);
    }

    @Test public void aTicketWithNeitherWritesNothingAndOneWithoutAnEntityCubeRestoresNone() {
        NBTTagCompound empty = new NBTTagCompound();
        CubicTicketData.write(empty, new Ticket().view(), null);
        assertFalse(empty.hasKey(CubicTicketData.TAG));

        Ticket ticket = new Ticket();
        ticket.forced.put(new ChunkPos(1, 1), new IntArraySet(new int[] {7}));
        NBTTagCompound forcedOnly = new NBTTagCompound();
        CubicTicketData.write(forcedOnly, ticket.view(), null);
        assertFalse(forcedOnly.getCompoundTag(CubicTicketData.TAG).hasKey(CubicTicketData.ENTITY_CUBE_Y));
        Ticket loaded = new Ticket();
        CubicTicketData.read(forcedOnly, loaded.view());
        assertNull(loaded.entityCubeY);
        assertEquals(ticket.forced, loaded.forced);
    }

    @Test public void aTicketSavedWithoutCubicDataLoadsEmpty() {
        Ticket loaded = new Ticket();
        CubicTicketData.read(new NBTTagCompound(), loaded.view());
        assertNull(loaded.entityCubeY);
        assertTrue(loaded.forced.isEmpty());
    }
}
