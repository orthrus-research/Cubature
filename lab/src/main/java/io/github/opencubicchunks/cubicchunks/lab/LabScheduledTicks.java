package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabScheduledTickProbe;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;

import java.util.*;

/** Small contained vanilla fixtures; original block states are restored on completion or cancellation. */
final class LabScheduledTicks {
    private final WorldServer world;
    private final Map<BlockPos, IBlockState> previous = new LinkedHashMap<>();
    private final Map<BlockPos, Block> expected = new LinkedHashMap<>();
    private final Map<BlockPos, Block> scheduled = new LinkedHashMap<>();
    private final Map<BlockPos, Long> requested = new HashMap<>();
    private final Set<BlockPos> complete = new HashSet<>();
    private int serializedEntries;

    LabScheduledTicks(WorldServer world) { this.world = world; }
    void prepare(LabJob job) {
        for (int cubeY = job.minY >> 4; cubeY < job.maxY >> 4; cubeY++) {
            CubePos cubePos = new CubePos(130, cubeY, 130);
            Cube cube = job.provider.getLoadedCube(cubePos);
            int y = Math.min(cubePos.getMinBlockY() + 8, job.maxY - 9);
            BlockPos torch = new BlockPos(cubePos.getMinBlockX() + 4, y, cubePos.getMinBlockZ() + 4);
            set(torch.down(), Blocks.STONE.getDefaultState());
            set(torch.up(), Blocks.AIR.getDefaultState());
            for (EnumFacing side : EnumFacing.HORIZONTALS) set(torch.offset(side), Blocks.AIR.getDefaultState());
            arm(torch, Blocks.UNLIT_REDSTONE_TORCH, Blocks.REDSTONE_TORCH, 2);

            BlockPos water = torch.east(6);
            set(water.down(), Blocks.STONE.getDefaultState());
            set(water.up(), Blocks.AIR.getDefaultState());
            for (EnumFacing side : EnumFacing.HORIZONTALS) set(water.offset(side), Blocks.STONE.getDefaultState());
            arm(water, Blocks.FLOWING_WATER, Blocks.WATER, 5);
            LabScheduledTickProbe.verify(cube, torch, water);
            serializedEntries += 2;
        }
    }
    private void set(BlockPos position, IBlockState state) {
        previous.putIfAbsent(position, world.getBlockState(position));
        world.setBlockState(position, state, 2);
    }
    private void arm(BlockPos position, Block initial, Block result, int delay) {
        set(position, initial.getDefaultState());
        scheduled.put(position, initial); expected.put(position, result); requested.put(position, System.nanoTime());
        world.scheduleUpdate(position, initial, delay);
        world.scheduleUpdate(position, initial, delay); // Real scheduler must deduplicate the same block/position.
    }
    boolean observe(LabJob job) {
        for (Map.Entry<BlockPos, Block> entry : expected.entrySet()) {
            if (complete.contains(entry.getKey()) || world.getBlockState(entry.getKey()).getBlock() != entry.getValue()) continue;
            complete.add(entry.getKey()); job.operationCount++;
            job.operations.add(System.nanoTime() - requested.get(entry.getKey()));
        }
        if (complete.size() != expected.size()) return false;
        for (Map.Entry<BlockPos, Block> entry : scheduled.entrySet()) {
            for (NextTickListEntry pending : ((ICubicWorldInternal.Server) world).getScheduledTicks().getForCube(CubePos.fromBlockCoords(entry.getKey()))) {
                if (pending.position.equals(entry.getKey()) && pending.getBlock() == entry.getValue())
                    throw new IllegalStateException("Completed update remains in the cube tick index at " + entry.getKey());
            }
        }
        return true;
    }
    Map<String, Object> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixtureCubeLevels", expected.size() / 2); data.put("expectedUpdates", expected.size());
        data.put("completedUpdates", complete.size()); data.put("serializedPendingEntriesVerified", serializedEntries);
        data.put("blocks", "unlit redstone torch -> lit redstone torch; contained flowing water -> still water");
        data.put("boundary", "Scheduled callbacks and pending NBT checked across all allocated cube levels; no delayed-tick disk-restart test.");
        return data;
    }
    void restore() {
        for (Map.Entry<BlockPos, IBlockState> entry : previous.entrySet()) world.setBlockState(entry.getKey(), entry.getValue(), 2);
        previous.clear();
    }
}
