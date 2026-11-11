package io.github.opencubicchunks.cubicchunks.core.world.chunkloader;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.common.util.Constants;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * A ticket's cubic data in Forge's ticket NBT, under {@code cubicchunks}: the cube Y of each forced cube by column, and
 * for an entity ticket the cube its entity is stored in. Forge records that entity's column ({@code chunkX},
 * {@code chunkZ}); {@code entityCubeY} completes the position, so loading finds the entity in its own cube.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class CubicTicketData {
    static final String TAG = "cubicchunks";
    static final String ENTITY_CUBE_Y = "entityCubeY";

    private CubicTicketData() {}

    /** Writes the tag when the ticket forces cubes or names an entity cube; a null entityCubeY writes no entity cube. */
    static void write(NBTTagCompound ticketNBT, ICubicTicketInternal ticket, @Nullable Integer entityCubeY) {
        Map<ChunkPos, IntSet> forced = ticket.getAllForcedChunkCubes();
        if (forced.isEmpty() && entityCubeY == null) {
            return;
        }
        NBTTagCompound cubicNBT = new NBTTagCompound();
        if (entityCubeY != null) {
            cubicNBT.setInteger(ENTITY_CUBE_Y, entityCubeY);
        }
        NBTTagList chunkMap = new NBTTagList();
        forced.forEach((pos, cubes) -> {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("x", pos.x);
            entry.setInteger("z", pos.z);
            entry.setIntArray("cubes", cubes.toIntArray());
            chunkMap.appendTag(entry);
        });
        cubicNBT.setTag("chunkMap", chunkMap);
        ticketNBT.setTag(TAG, cubicNBT);
    }

    /**
     * Restores the forced cube Ys (only stored: mods request their columns in the loading callback, and the cubes
     * are forced then) and the entity cube, when the tag holds one.
     */
    static void read(NBTTagCompound ticketNBT, ICubicTicketInternal ticket) {
        NBTTagCompound cubicNBT = ticketNBT.getCompoundTag(TAG);
        if (cubicNBT.hasKey(ENTITY_CUBE_Y, Constants.NBT.TAG_INT)) {
            ticket.setEntityChunkY(cubicNBT.getInteger(ENTITY_CUBE_Y));
        }
        Map<ChunkPos, IntSet> coordsMap = new HashMap<>();
        for (NBTBase entryTagBase : cubicNBT.getTagList("chunkMap", Constants.NBT.TAG_COMPOUND)) {
            NBTTagCompound entry = (NBTTagCompound) entryTagBase;
            coordsMap.put(new ChunkPos(entry.getInteger("x"), entry.getInteger("z")), new IntArraySet(entry.getIntArray("cubes")));
        }
        ticket.setAllForcedChunkCubes(coordsMap);
    }
}
