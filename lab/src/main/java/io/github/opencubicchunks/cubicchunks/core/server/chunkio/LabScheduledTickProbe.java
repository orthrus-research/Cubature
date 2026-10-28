package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;

/** Owner-thread check of registered vanilla ticks in the live cube index and actual NBT writer. */
public final class LabScheduledTickProbe {
    private LabScheduledTickProbe() { }

    public static void verify(Cube cube, BlockPos torch, BlockPos water) {
        NBTTagList tags = IONbtWriter.write(cube).getCompoundTag("Level").getTagList("TileTicks", 10);
        verifyEntry(cube, tags, torch, Blocks.UNLIT_REDSTONE_TORCH);
        verifyEntry(cube, tags, water, Blocks.FLOWING_WATER);
    }

    private static void verifyEntry(Cube cube, NBTTagList tags, BlockPos position, Block block) {
        int liveCount = 0, savedCount = 0;
        for (NextTickListEntry entry : ((ICubicWorldInternal.Server) cube.getWorld()).getScheduledTicks().getForCube(cube.getCoords())) {
            if (entry.position.equals(position) && entry.getBlock() == block) liveCount++;
        }
        String name = Block.REGISTRY.getNameForObject(block).toString();
        for (int i = 0; i < tags.tagCount(); i++) {
            NBTTagCompound tag = tags.getCompoundTagAt(i);
            if (tag.getInteger("x") == position.getX() && tag.getInteger("y") == position.getY() && tag.getInteger("z") == position.getZ()
                    && tag.getString("i").equals(name)) {
                if (tag.getInteger("t") <= 0) throw new IllegalStateException("Scheduled tick lost its future delay");
                savedCount++;
            }
        }
        if (liveCount != 1 || savedCount != 1) throw new IllegalStateException("Duplicate/missing cube tick at " + position
                + ": live=" + liveCount + ", serialized=" + savedCount);
    }
}
