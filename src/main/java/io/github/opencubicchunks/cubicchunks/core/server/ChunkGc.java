/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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
package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
import java.util.Iterator;

/**
 * Chunk Garbage Collector, automatically unloads unused chunks.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class ChunkGc {

    private final CubeProviderServer cubeCache;
    /** The provider's incremental maintenance, or null when each interval sweeps everything. */
    @Nullable private final MaintenanceState maintenance;

    private int tick = 0;

    public ChunkGc(CubeProviderServer cubeCache) {
        this.cubeCache = cubeCache;
        this.maintenance = cubeCache.maintenance();
    }

    public void tick() {
        cubeCache.world.profiler.startSection("chunkGc");
        tick++;
        if (tick > CubicChunksConfig.chunkGCInterval) {
            tick = 0;
            if (maintenance != null) beginIncrementalGc();
            else chunkGc();
        }
        if (maintenance != null) tickIncrementalGc();
        if (CubicChunks.DEBUG_ENABLED) {
            verifyColumnConsistency();
        }
        cubeCache.world.profiler.endSection();
    }

    /** Incremental maintenance: start a GC pass over every loaded cube and column, once per interval. */
    private void beginIncrementalGc() {
        if (maintenance != null && maintenance.gcCubes == 0 && maintenance.gcColumns == 0) {
            maintenance.gcCubes = maintenance.cubes.size();
            maintenance.gcColumns = maintenance.columns.size();
        }
    }

    /** Incremental maintenance: inspect and unload within this tick's maintenance budget. */
    private void tickIncrementalGc() {
        if (maintenance == null) return;
        long start = System.nanoTime();
        long budget = CubicChunksConfig.maintenanceTimeMillis * 1_000_000L;
        int unloaded = 0;
        for (int inspected = 0; inspected < CubicChunksConfig.maintenanceInspectionsPerTick
                && (maintenance.gcCubes > 0 || maintenance.gcColumns > 0) && unloaded < CubicChunksConfig.maintenanceUnloadsPerTick
                && (inspected == 0 || System.nanoTime() - start < budget); inspected++) {
            maintenance.gcColumnFirst = !maintenance.gcColumnFirst;
            if (maintenance.gcColumns > 0 && (maintenance.gcColumnFirst || maintenance.gcCubes == 0)) {
                maintenance.gcColumns--;
                Chunk column = maintenance.columns.next(false);
                if (column == null) maintenance.gcColumns = 0;
                else if (cubeCache.loadedChunks.get(ChunkPos.asLong(column.x, column.z)) != column) maintenance.columns.remove(column);
                else if (cubeCache.tryUnloadColumn(column)) {
                    if (cubeCache.loadedChunks.get(ChunkPos.asLong(column.x, column.z)) == column)
                        cubeCache.loadedChunks.remove(ChunkPos.asLong(column.x, column.z));
                    maintenance.columns.remove(column);
                    unloaded++;
                }
            } else {
                maintenance.gcCubes--;
                Cube cube = maintenance.cubes.next(false);
                if (cube == null) maintenance.gcCubes = 0;
                else if (cubeCache.getLoadedCube(cube.getX(), cube.getY(), cube.getZ()) != cube) maintenance.cubes.remove(cube);
                else if (cubeCache.tryUnloadCube(cube)) {
                    if (cubeCache.getLoadedCube(cube.getX(), cube.getY(), cube.getZ()) == cube) cubeCache.forgetCube(cube);
                    maintenance.cubes.remove(cube);
                    unloaded++;
                }
            }
        }
    }

    private void verifyColumnConsistency() {
        // currently do that every tick, until I'm sure it doesn't happen
        Iterator<Cube> cubeIt = cubeCache.cubesIterator();
        while (cubeIt.hasNext()) {
            Cube cube = cubeIt.next();
            IColumn cubeCol = cube.getColumn();
            Chunk storedCol = cubeCache.getLoadedColumn(cube.getX(), cube.getZ());
            if (storedCol == null) {
                throw new RuntimeException("Cube with no stored column!");
            }
            //noinspection EqualsBetweenInconvertibleTypes
            if (storedCol != cubeCol) {
                throw new RuntimeException("CubeColumn and StoredColumn are different!");
            }
        }

        Iterator<Chunk> columnIt = cubeCache.columnsIterator();
        int totalCubes = 0;
        while (columnIt.hasNext()) {
            Chunk storedCol = columnIt.next();
            @SuppressWarnings("unchecked")
            Collection<Cube> storedColumnCubes = (Collection<Cube>) ((IColumn) storedCol).getLoadedCubes();
            for (Cube c : storedColumnCubes) {
                if (cubeCache.getLoadedCube(c.getCoords()) != c) {
                    throw new RuntimeException("Cube in column not the same as stored cube!");
                }
            }
            totalCubes += storedColumnCubes.size();
        }
        if (totalCubes != cubeCache.getLoadedCubeCount()) {
            throw new RuntimeException("Counted " + totalCubes + " cubes in columns, but there are total of " + cubeCache.getLoadedCubeCount() + " cubes!");
        }
    }

    public void chunkGc() {
        Iterator<Cube> cubeIt = cubeCache.cubesIterator();
        while (cubeIt.hasNext()) {
            if (cubeCache.tryUnloadCube(cubeIt.next())) {
                cubeIt.remove();
            }
        }

        Iterator<Chunk> columnIt = cubeCache.columnsIterator();
        while (columnIt.hasNext()) {
            if (cubeCache.tryUnloadColumn(columnIt.next())) {
                columnIt.remove();
            }
        }
    }
}
