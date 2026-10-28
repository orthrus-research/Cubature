package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional lab fixture in the provider package to exercise its normal unload admission and iterator removal. */
public final class LabMapChurnProbe {
    private final List<Cube> originals = new ArrayList<>();
    private int waypoints, removed, reloaded, checkedCubes, checkedColumns;
    private long flushNanos;

    public boolean observe(WorldServer world, Collection<EntityPlayerMP> players, int tick) throws IOException {
        CubeProviderServer provider = (CubeProviderServer) world.getChunkProvider();
        if (tick <= 4) for (int count = 0; count < 2; count++) {
            int x = 400 + originals.size();
            Cube cube = provider.getCubeNow(x, 5, 400, Requirement.GENERATE);
            if (cube == null) throw new IllegalStateException("Map fixture cube missing");
            cube.setBlockState(marker(cube), Blocks.GOLD_BLOCK.getDefaultState());
            cube.markDirty(); provider.getCubeIO().saveCube(cube); originals.add(cube);
        }
        if ((tick - 1) % 20 == 0) {
            int step = (tick - 1) / 20;
            if (players.isEmpty()) throw new IllegalStateException("Map churn requires a joined player");
            for (EntityPlayerMP player : players) {
                player.setGameType(GameType.SPECTATOR);
                player.connection.setPlayerLocation(2048.5 + (step == 8 ? 0 : step * 16), 80, 2048.5, 0, 15);
            }
            waypoints++;
        }
        if (tick < 180) return false;
        long start = System.nanoTime(); provider.getCubeIO().flush(); flushNanos = System.nanoTime() - start;
        int before = provider.getLoadedCubeCount();
        for (Iterator<Cube> iterator = provider.cubesIterator(); iterator.hasNext();) {
            Cube cube = iterator.next();
            if (!originals.contains(cube)) continue;
            if (!provider.tryUnloadCube(cube)) throw new IllegalStateException("Fixture unexpectedly ticketed");
            iterator.remove(); removed++;
        }
        if (removed != 8 || before - provider.getLoadedCubeCount() != 8)
            throw new IllegalStateException("Map removal count differs from fixture");
        for (Cube old : originals) {
            if (provider.getLoadedCube(old.getCoords()) != null) throw new IllegalStateException("Removed cube still indexed");
            Cube loaded = provider.getCubeNow(old.getX(), old.getY(), old.getZ(), Requirement.LOAD);
            if (loaded == null || loaded == old || loaded.getBlockState(marker(old)).getBlock() != Blocks.GOLD_BLOCK)
                throw new IllegalStateException("Reload did not restore the saved marker in a fresh cube");
            reloaded++;
        }
        for (Iterator<Cube> iterator = provider.cubesIterator(); iterator.hasNext();) {
            Cube cube = iterator.next();
            if (provider.getLoadedCube(cube.getCoords()) != cube ||
                    provider.getLoadedColumn(cube.getX(), cube.getZ()) != cube.getColumn() ||
                    ((IColumn) cube.getColumn()).getLoadedCube(cube.getY()) != cube)
                throw new IllegalStateException("Cube/column index ownership differs");
            checkedCubes++;
        }
        int columnCubeCount = 0;
        for (Iterator<Chunk> iterator = provider.columnsIterator(); iterator.hasNext();) {
            Chunk column = iterator.next(); checkedColumns++;
            for (io.github.opencubicchunks.cubicchunks.api.world.ICube cube : ((IColumn) column).getLoadedCubes()) {
                if (provider.getLoadedCube(cube.getX(), cube.getY(), cube.getZ()) != cube)
                    throw new IllegalStateException("Column cube missing from global index");
                columnCubeCount++;
            }
        }
        if (checkedCubes != provider.getLoadedCubeCount() || columnCubeCount != checkedCubes || waypoints != 9)
            throw new IllegalStateException("Loaded cube counts or route coverage differ");
        return true;
    }

    private static BlockPos marker(Cube cube) {
        return new BlockPos(cube.getX() * 16 + 8, cube.getY() * 16 + 8, cube.getZ() * 16 + 8);
    }

    public Map<String, Object> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waypoints", waypoints); data.put("removedCubes", removed); data.put("reloadedMarkers", reloaded);
        data.put("checkedLoadedCubes", checkedCubes); data.put("checkedColumns", checkedColumns);
        data.put("blockingFlushNanos", flushNanos);
        data.put("notes", "Eight one-cube camera positions and return; eight distant markers unloaded through normal eligibility and iterator removal, then read after flush. Owner-thread index audit and flush are included. This is a correctness workload, not an isolated map timing or fresh-JVM reload.");
        return data;
    }
}
