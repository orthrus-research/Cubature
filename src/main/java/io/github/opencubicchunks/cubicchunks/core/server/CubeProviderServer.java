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

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.storage.StorageFormatProviderBase;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.DeliveryMode;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.AsyncBatchingCubeIO;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.ICubeIO;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge.AsyncWorldIOExecutor;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer;
import io.github.opencubicchunks.cubicchunks.api.util.Box;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.util.XYZMap;
import io.github.opencubicchunks.cubicchunks.core.world.ICubeProviderInternal;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.core.world.WorldSavedCubicChunksData;
import io.github.opencubicchunks.cubicchunks.core.world.cube.BlankCube;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.StartupQuery;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Detainted;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * This is CubicChunks equivalent of ChunkProviderServer, it loads and unloads Cubes and Columns.
 * <p>
 * There are a few necessary changes to the way vanilla methods work:
 * * Because loading a Chunk (Column) doesn't make much sense with CubicChunks,
 * all methods that load Chunks, actually load  an empry column with no blocks in it
 * (there may be some entities that are not in any Cube yet).
 * * dropChunk method is not supported. Columns are unloaded automatically when the last cube is unloaded
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class CubeProviderServer extends ChunkProviderServer implements ICubeProviderServer, ICubeProviderInternal.Server {

    @Nonnull private final EmptyColumn emptyColumn;
    @Nonnull private final BlankCube emptyCube;

    @Nonnull private final WorldServer worldServer;
    @Nonnull private final ICubeIO cubeIO;

    // TODO: Use a better hash map!
    @Nonnull private final XYZMap<Cube> cubeMap = new XYZMap<>(0.7f, 8000);
    private final PacedAutosave<CubePos> autosaveCubes = new PacedAutosave<>();
    private final PacedAutosave<ChunkPos> autosaveColumns = new PacedAutosave<>();
    private int lastAutosaveProcessed;
    private boolean autosaveColumnsFirst;
    private final DeliveryMode deliveryMode;
    /** Present exactly when the delivery mode inspects incrementally. */
    @Nullable private final MaintenanceState maintenance;

    @Nonnull private final CubePrimer cubePrimer;
    @Nonnull private final ICubeGenerator cubeGen;
    @Nullable private final ConcurrentGeneration concurrentGeneration;
    @Nonnull private final Profiler profiler;
    // some mods will try to access blocks in ChunkDataEvent.Load
    // this needs the column to be already known by the chunk provider so that it can load cubes without trying to load the column again
    private final LoadingColumnContext loadingColumns = new LoadingColumnContext();

    public CubeProviderServer(WorldServer worldServer, ICubeGenerator cubeGen, DeliveryMode deliveryMode) {
        super(worldServer,
                worldServer.getSaveHandler().getChunkLoader(worldServer.provider), // forge uses this in
                worldServer.provider.createChunkGenerator()); // let's create the chunk generator, for now the vanilla one may be enough

        this.cubePrimer = new CubePrimer();
        this.cubeGen = cubeGen;
        this.deliveryMode = deliveryMode;
        this.maintenance = deliveryMode.incremental() ? new MaintenanceState(deliveryMode.continuousSave()) : null;
        this.concurrentGeneration = ConcurrentGeneration.create(cubeGen, "dimension " + worldServer.provider.getDimension());
        this.worldServer = worldServer;
        this.profiler = worldServer.profiler;
        try {
            Path path = worldServer.getSaveHandler().getWorldDirectory().toPath();
            if (worldServer.provider.getSaveFolder() != null) {
                path = path.resolve(worldServer.provider.getSaveFolder());
            }

            //use the save format stored in the server's default world as the global world storage type
            World overworld = worldServer.getMinecraftServer().getEntityWorld();

            WorldSavedCubicChunksData savedData =
                    (WorldSavedCubicChunksData) overworld.getPerWorldStorage().getOrLoadData(WorldSavedCubicChunksData.class, "cubicChunksData");

            StorageFormatProviderBase format = StorageFormatProviderBase.REGISTRY.getValue(savedData.storageFormat);
            if (format == null) {
                StartupQuery.notify("unsupported storage format \"" + savedData.storageFormat + '"');
                StartupQuery.abort();
            }

            this.cubeIO = new AsyncBatchingCubeIO(worldServer, format.provideStorage(worldServer, path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        this.emptyColumn = new EmptyColumn(worldServer, 0, 0);
        this.emptyCube = new BlankCube(emptyColumn);
    }

    @Override
    @Detainted
    public void queueUnload(Chunk chunk) {
        //ignore, ChunkGc unloads cubes
    }

    @Override
    @Detainted
    public void queueUnloadAll() {
        //ignore, ChunkGc unloads cubes
    }

    /**
     * Vanilla method, returns a Chunk (Column) only of it's already loaded.
     */
    @Nullable @Override
    public Chunk getLoadedColumn(int columnX, int columnZ) {
        Chunk chunk = this.loadedChunks.get(ChunkPos.asLong(columnX, columnZ));
        return chunk == null ? loadingColumns.get(columnX, columnZ) : chunk;
    }

    @Nullable
    @Override
    @Deprecated
    public Chunk getLoadedChunk(int columnX, int columnZ) {
        return (Chunk) getLoadedColumn(columnX, columnZ);
    }

    /**
     * Loads Chunk (Column) if it can be loaded from disk, or returns already loaded one.
     * Doesn't generate new Columns.
     */
    @Nullable
    @Override
    @Deprecated
    public Chunk loadChunk(int columnX, int columnZ) {
        return this.loadChunk(columnX, columnZ, null);
    }

    /**
     * Load chunk asynchronously. Currently CubicChunks only loads synchronously.
     */
    @Nullable
    @Override
    @Deprecated
    public Chunk loadChunk(int columnX, int columnZ, @Nullable Runnable runnable) {
        if (runnable == null) {
            return getColumn(columnX, columnZ, Requirement.LOAD);
        }
        asyncGetColumn(columnX, columnZ, Requirement.LOAD, col -> runnable.run(), runnable);
        return null;
    }

    /**
     * If this Column is already loaded - returns it.
     * Loads from disk if possible, otherwise generates new Column.
     */
    @Override
    public Chunk provideColumn(int cubeX, int cubeZ) {
        return getColumn(cubeX, cubeZ, Requirement.GENERATE);
    }

    @Override
    @Deprecated
    public Chunk provideChunk(int cubeX, int cubeZ) {
        return (Chunk) provideColumn(cubeX, cubeZ);
    }

    /** Saves every dirty cube and column now. The periodic autosave uses {@link #beginPeriodicAutosave()}. */
    @Override
    public boolean saveChunks(boolean alwaysTrue) {
        // Lighting can dirty cubes that the scan would otherwise skip, including cubes before
        // the first dirty entry. Complete queued updates before selecting any save candidates.
        ((ICubicWorldInternal) worldServer).getLightingManager().processUpdates();
        for (Cube cube : cubeMap) { // save cubes
            if (cube.needsSaving()) {
                this.cubeIO.saveCube(cube);
            }
        }
        for (Chunk chunk : loadedChunks.values()) { // save columns
            // save the column
            if (chunk.needsSaving(alwaysTrue)) {
                this.cubeIO.saveColumn(chunk);
            }
        }
        autosaveCubes.clear();
        autosaveColumns.clear();
        if (maintenance != null) maintenance.discoverCubes = maintenance.discoverColumns = 0;
        return true;
    }

    /**
     * The periodic autosave: selects what is dirty now, and provider ticks snapshot it within the
     * autosave budget. Only the server's periodic save calls this; {@link #saveChunks} saves at once.
     */
    public void beginPeriodicAutosave() {
        ((ICubicWorldInternal) worldServer).getLightingManager().processUpdates();
        if (maintenance != null) {
            maintenance.discoverCubes = Math.max(maintenance.discoverCubes, maintenance.cubes.size());
            maintenance.discoverColumns = Math.max(maintenance.discoverColumns, maintenance.columns.size());
            return;
        }
        for (Cube cube : cubeMap) if (cube.needsSaving()) autosaveCubes.add(cube.getCoords());
        for (Chunk chunk : loadedChunks.values()) if (chunk.needsSaving(true)) autosaveColumns.add(chunk.getPos());
        // Continuations run in provider ticks. The initial scan does not serialize dirty cubes.
    }

    private void tickAutosave() {
        lastAutosaveProcessed = 0;
        if (worldServer.disableLevelSaving) return;
        long start = System.nanoTime();
        long budget = Math.max(1, CubicChunksConfig.autosaveTimeMillis) * 1_000_000L;
        int limit = Math.max(1, CubicChunksConfig.autosaveSnapshotsPerTick);
        if (maintenance != null && maintenance.continuousDiscovery
                && maintenance.discoverCubes == 0 && maintenance.discoverColumns == 0) {
            // The existing two independent bounded cursors discover dirty state continuously.
            // Snapshot publication still runs on this owner thread and honors writer backpressure.
            maintenance.discoverCubes = maintenance.cubes.size();
            maintenance.discoverColumns = maintenance.columns.size();
        }
        discoverAutosave(start, budget / 2);
        // Alternate priority, including with a quota of one or an over-budget first snapshot.
        autosaveColumnsFirst = !autosaveColumnsFirst;
        long remaining = budget - (System.nanoTime() - start);
        if (autosaveColumnsFirst) lastAutosaveProcessed = tickAutosaveColumns(Math.min(4, limit), Math.max(1, remaining));
        remaining = budget - (System.nanoTime() - start);
        if (remaining > 0 && lastAutosaveProcessed < limit) lastAutosaveProcessed += autosaveCubes.tick(limit - lastAutosaveProcessed, remaining, cubeIO::canAutoSave, pos -> {
            Cube cube = getLoadedCube(pos);
            if (cube != null && cube.needsSaving()) cubeIO.saveCube(cube);
        }, System::nanoTime);
        remaining = budget - (System.nanoTime() - start);
        if (!autosaveColumnsFirst && remaining > 0 && lastAutosaveProcessed < limit)
            lastAutosaveProcessed += tickAutosaveColumns(Math.min(4, limit - lastAutosaveProcessed), remaining);
    }

    private int tickAutosaveColumns(int limit, long budget) {
        return autosaveColumns.tick(limit, budget, cubeIO::canAutoSave, pos -> {
            Chunk column = getLoadedColumn(pos.x, pos.z);
            if (column != null && column.needsSaving(true)) cubeIO.saveColumn(column);
        }, System::nanoTime);
    }

    /** Pending owner-thread snapshots, distinct from the writer's serialized-NBT backlog. */
    public int getPendingAutosaveCount() { return autosaveCubes.size() + autosaveColumns.size(); }
    public int getLastAutosaveProcessedCount() { return lastAutosaveProcessed; }

    public int getPendingAutosaveDiscoveryCount() {
        return maintenance == null ? 0 : maintenance.discoverCubes + maintenance.discoverColumns;
    }

    private void discoverAutosave(long start, long budget) {
        if (maintenance == null) return;
        for (int inspected = 0; inspected < CubicChunksConfig.maintenanceInspectionsPerTick
                && (maintenance.discoverCubes > 0 || maintenance.discoverColumns > 0)
                && getPendingAutosaveCount() < CubicChunksConfig.autosavePendingThreshold
                && (inspected == 0 || System.nanoTime() - start < budget); inspected++) {
            maintenance.discoveryColumnFirst = !maintenance.discoveryColumnFirst;
            if (maintenance.discoverColumns > 0 && (maintenance.discoveryColumnFirst || maintenance.discoverCubes == 0)) {
                maintenance.discoverColumns--;
                Chunk column = maintenance.columns.next(true);
                if (column == null) maintenance.discoverColumns = 0;
                else if (column.needsSaving(true)) autosaveColumns.add(column.getPos());
            } else {
                maintenance.discoverCubes--;
                Cube cube = maintenance.cubes.next(true);
                if (cube == null) maintenance.discoverCubes = 0;
                else if (cube.needsSaving()) autosaveCubes.add(cube.getCoords());
            }
        }
    }

    DeliveryMode getDeliveryMode() { return deliveryMode; }

    boolean usesIncrementalMaintenance() { return maintenance != null; }

    void beginIncrementalGc() {
        if (maintenance != null && maintenance.gcCubes == 0 && maintenance.gcColumns == 0) {
            maintenance.gcCubes = maintenance.cubes.size();
            maintenance.gcColumns = maintenance.columns.size();
        }
    }

    void tickIncrementalGc() {
        if (maintenance == null) return;
        long start = System.nanoTime();
        long budget = Math.max(1, CubicChunksConfig.maintenanceTimeMillis) * 1_000_000L;
        int unloaded = 0;
        for (int inspected = 0; inspected < CubicChunksConfig.maintenanceInspectionsPerTick
                && (maintenance.gcCubes > 0 || maintenance.gcColumns > 0) && unloaded < CubicChunksConfig.maintenanceUnloadsPerTick
                && (inspected == 0 || System.nanoTime() - start < budget); inspected++) {
            maintenance.gcColumnFirst = !maintenance.gcColumnFirst;
            if (maintenance.gcColumns > 0 && (maintenance.gcColumnFirst || maintenance.gcCubes == 0)) {
                maintenance.gcColumns--;
                Chunk column = maintenance.columns.next(false);
                if (column == null) maintenance.gcColumns = 0;
                else if (loadedChunks.get(ChunkPos.asLong(column.x, column.z)) != column) maintenance.columns.remove(column);
                else if (tryUnloadColumn(column)) {
                    if (loadedChunks.get(ChunkPos.asLong(column.x, column.z)) == column)
                        loadedChunks.remove(ChunkPos.asLong(column.x, column.z));
                    maintenance.columns.remove(column);
                    unloaded++;
                }
            } else {
                maintenance.gcCubes--;
                Cube cube = maintenance.cubes.next(false);
                if (cube == null) maintenance.gcCubes = 0;
                else if (getLoadedCube(cube.getX(), cube.getY(), cube.getZ()) != cube) maintenance.cubes.remove(cube);
                else if (tryUnloadCube(cube)) {
                    if (getLoadedCube(cube.getX(), cube.getY(), cube.getZ()) == cube) cubeMap.remove(cube);
                    maintenance.cubes.remove(cube);
                    unloaded++;
                }
            }
        }
    }

    @Override
    public boolean tick() {
        // NOTE: the return value is completely ignored
        profiler.startSection("providerTick");
        tickAutosave();
        long i = System.currentTimeMillis();
        Random rand = this.world.rand;
        Iterator<Cube> watchersIterator = demand().getCubeIterator();
        BooleanSupplier tickFaster = () -> System.currentTimeMillis() - i > 40;
        while (watchersIterator.hasNext()) {
            watchersIterator.next().tickCubeServer(tickFaster, rand);
        }
        profiler.endSection();
        return false;
    }

    @Override
    public String makeString() {
        return "CubeProviderServer: " + this.loadedChunks.size() + " columns, "
                + this.cubeMap.getSize() + " cubes";
    }

    @Override
    public List<Biome.SpawnListEntry> getPossibleCreatures(final EnumCreatureType type, final BlockPos pos) {
        return cubeGen.getPossibleCreatures(type, pos);
    }

    @Nullable @Override
    public BlockPos getNearestStructurePos(World worldIn, String name, BlockPos pos, boolean findUnexplored) {
        return cubeGen.getClosestStructure(name, pos, findUnexplored);
    }

    // getLoadedChunkCount() in ChunkProviderServer is fine - CHECKED: 1.10.2-12.18.1.2092

    @Override
    public boolean chunkExists(int cubeX, int cubeZ) {
        return this.loadedChunks.get(ChunkPos.asLong(cubeX, cubeZ)) != null;
    }

    @Override // TODO: What it does? implement it
    public boolean isInsideStructure(World p_193413_1_, String p_193413_2_, BlockPos p_193413_3_) {
        return false;
    }

    //==============================
    //=====CubicChunks methods======
    //==============================

    @Override
    public Cube getCube(int cubeX, int cubeY, int cubeZ) {
        return getCube(cubeX, cubeY, cubeZ, Requirement.GENERATE);
    }

    @Override
    public Cube getCube(CubePos coords) {
        return getCube(coords.getX(), coords.getY(), coords.getZ());
    }

    @Nullable @Override
    public Cube getLoadedCube(int cubeX, int cubeY, int cubeZ) {
        return cubeMap.get(cubeX, cubeY, cubeZ);
    }

    @Nullable @Override
    public Cube getLoadedCube(CubePos coords) {
        return getLoadedCube(coords.getX(), coords.getY(), coords.getZ());
    }

    /**
     * Load a cube, asynchronously. The work done to retrieve the column is specified by the
     * {@link Requirement} <code>req</code>
     *
     * @param cubeX Cube x position
     * @param cubeY Cube y position
     * @param cubeZ Cube z position
     * @param req Work done to retrieve the column
     * @param callback Callback to be called when the load finishes. Note that <code>null</code> can be passed to the
     * callback if the work specified by <code>req</code> is not sufficient to provide a cube
     *
     * @see #getCube(int, int, int, Requirement) for the synchronous equivalent to this method
     */
    public void asyncGetCube(int cubeX, int cubeY, int cubeZ, Requirement req, Consumer<Cube> callback) {
        Cube cube = getLoadedCube(cubeX, cubeY, cubeZ);
        if (req == Requirement.GET_CACHED || (cube != null && req.compareTo(Requirement.GENERATE) <= 0)) {
            callback.accept(cube);
            return;
        }

        if (cube == null) {
            AsyncWorldIOExecutor.queueCubeLoad(worldServer, cubeIO, this, cubeX, cubeY, cubeZ,
                    AsyncWorldIOExecutor.withCallbackIdentity(callback, loaded -> {
                Chunk col = getLoadedColumn(cubeX, cubeZ);
                // A load-only parent miss must not generate terrain from the I/O completion drain.
                // Explicit generation requests still perform their requested postprocessing.
                if (col == null && req != Requirement.LOAD) {
                    col = getColumn(cubeX, cubeZ, req);
                    if (col != null && col.isEmpty()) {
                        callback.accept(emptyCube);
                        return;
                    }
                }
                if (col != null) {
                    assert !col.isEmpty();
                    onCubeLoaded(loaded, col);
                    // TODO: async loading in asyncGetCube?
                    loaded = postCubeLoadAttempt(cubeX, cubeY, cubeZ, loaded, col, req, Timing.POLL);
                }
                callback.accept(loaded);
            }));
        } else {
            callback.accept(postCubeLoadAttempt(cubeX, cubeY, cubeZ, cube, cube.getColumn(), req, Timing.POLL));
        }
    }

    /** When a request that needs generation gets it. */
    private enum Timing {
        /** On this thread once the generator is ready; with workers, a result they already hold is used. */
        POLL,
        /** Delivery: handed to the generation workers; the empty cube or column is returned until they finish. */
        SCHEDULE,
        /** On this thread now, even when the generator reports it is not ready. */
        NOW
    }

    @Nullable @Override
    public Cube getCube(int cubeX, int cubeY, int cubeZ, Requirement req) {
        return getCube(cubeX, cubeY, cubeZ, req, Timing.POLL);
    }

    @Nullable @Override
    public Cube getCubeNow(int cubeX, int cubeY, int cubeZ, Requirement req) {
        return getCube(cubeX, cubeY, cubeZ, req, Timing.NOW);
    }

    /** A delivery request: generation it needs, population included, goes to the workers instead of this thread. */
    @Nullable
    Cube getCubeForDelivery(int cubeX, int cubeY, int cubeZ, Requirement req) {
        return getCube(cubeX, cubeY, cubeZ, req, Timing.SCHEDULE);
    }

    @Nullable
    private Cube getCube(int cubeX, int cubeY, int cubeZ, Requirement req, Timing timing) {
        Cube cube = getLoadedCube(cubeX, cubeY, cubeZ);
        if (req == Requirement.GET_CACHED ||
                (cube != null && req.compareTo(Requirement.GENERATE) <= 0)) {
            return cube;
        }

        // try to get the Column
        Chunk column = getColumn(cubeX, cubeZ, req, timing);
        if (column == null) {
            return cube; // Column did not reach req, so Cube also does not
        }
        if (column.isEmpty()) {
            return emptyCube;
        }

        if (cube == null) {
            // loading a column may cause loading a cube when a mod accesses blocks in chunk data load event, check if the cube became loaded
            cube = getLoadedCube(cubeX, cubeY, cubeZ);
        }

        if (cube == null) {
            // a little hack to fix StackOverflowError when loading TileEntities, as Cube methods are now redirected into IColumn
            // Column needs cube to be loaded to add TileEntity, so make CubeProvider contain it already
            cube = AsyncWorldIOExecutor.syncCubeLoad(worldServer, cubeIO, this, cubeX, cubeY, cubeZ);
            onCubeLoaded(cube, column);
        }

        return postCubeLoadAttempt(cubeX, cubeY, cubeZ, cube, column, req, timing);
    }

    @Override public boolean isCubeGenerated(int cubeX, int cubeY, int cubeZ) {
        return getLoadedCube(cubeX, cubeY, cubeZ) != null || cubeIO.cubeExists(cubeX, cubeY, cubeZ);
    }

    /**
     * After successfully loading a cube, add it to it's column and the lookup table
     *
     * @param cube The cube that was loaded
     * @param column The column of the cube
     */
    private void onCubeLoaded(@Nullable Cube cube, Chunk column) {
        if (cube != null) {
            Cube previous = cubeMap.put(cube); // cache the Cube
            if (maintenance != null) {
                if (previous != cube) maintenance.cubes.remove(previous);
                maintenance.cubes.add(cube);
            }
            //synchronous loading may cause it to be called twice when async loading has been already queued
            //because AsyncWorldIOExecutor only executes one task for one cube and because only saving a cube
            //can modify one that is being loaded, it's impossible to end up with 2 versions of the same cube
            //This is only to prevents multiple callbacks for the same queued load from adding the same cube twice.
            if (!((IColumn) column).getLoadedCubes().contains(cube)) {
                ((IColumn) column).addCube(cube);
                cube.onLoad(); // init the Cube
            }
        }
    }

    /**
     * Process a recently loaded cube as per the specified effort level.
     *
     * @param cubeX Cube x position
     * @param cubeY Cube y position
     * @param cubeZ Cube z positon
     * @param cube The loaded cube, if loaded, else <code>null</code>
     * @param column The column of the cube
     * @param req Work done on the cube
     *
     * @return The processed cube, or <code>null</code> if the effort level is not sufficient to provide a cube
     */
    @Nullable
    private Cube postCubeLoadAttempt(int cubeX, int cubeY, int cubeZ, @Nullable Cube cube, Chunk column, Requirement req, Timing timing) {
        // when async load+generate request is immediately followed by sync request,  the async one will generate the cube in callback, but it won't
        // change the async load request result, so the cube here will still be null. Just to make sure, get the cube here
        // otherwise we may end up generating the same cube twice
        if (cube == null) {
            cube = getLoadedCube(cubeX, cubeY, cubeZ);
        }
        // Fast path - Nothing to do here
        if (req == Requirement.LOAD) {
            return cube;
        }
        if (req == Requirement.GENERATE && cube != null) {
            return cube;
        }
        if (cube == null) {
            if (timing != Timing.NOW && pollCube(cubeX, cubeY, cubeZ, timing) != ICubeGenerator.GeneratorReadyState.READY) {
                return emptyCube;
            }
            // generate the Cube
            cube = generateCube(cubeX, cubeY, cubeZ, column, timing == Timing.NOW).orElse(null);
            if (cube == null) {
                return emptyCube;
            }
            if (req == Requirement.GENERATE) {
                return cube;
            }
        }

        if (!cube.isFullyPopulated()) {
            if (timing != Timing.NOW && cubeGen.pollAsyncCubePopulator(cubeX, cubeY, cubeZ) != ICubeGenerator.GeneratorReadyState.READY) {
                return emptyCube;
            }
            // forced full population of this cube
            if (!populateCube(cube, timing)) {
                return cube;
            }
            if (req == Requirement.POPULATE) {
                return cube;
            }
        }

        if (!cube.isInitialLightingDone() || !cube.isSurfaceTracked()) {
            calculateDiffuseSkylight(cube);
        }
        if (!cube.isSurfaceTracked()) {
            cube.trackSurface();
        }

        return cube;
    }


    /**
     * Generate a cube at the specified position
     *
     * @param cubeX Cube x position
     * @param cubeY Cube y position
     * @param cubeZ Cube z position
     * @param column Column of the cube
     *
     * @return The generated cube
     */
    private Optional<Cube> generateCube(int cubeX, int cubeY, int cubeZ, Chunk column, boolean forceGenerate) {
        CubePrimer prepared = concurrentGeneration == null ? null : concurrentGeneration.takeCube(cubeX, cubeY, cubeZ);
        return (prepared != null ? Optional.of(prepared) : cubeGen.tryGenerateCube(cubeX, cubeY, cubeZ, this.cubePrimer, forceGenerate))
                .map(primer -> {
                    Cube cube = new Cube(column, cubeY, primer);
                    onCubeLoaded(cube, column);
                    // don't bother resetting the primer if it wasn't used by the generator.
                    // if the generator modifies the primer and then returns a different one it's
                    // not implementing generateCube correctly, so we don't account for that case
                    if (primer == this.cubePrimer) {
                        primer.reset();
                    }
                    return cube;
                });
    }

    /**
     * Populate a cube at the specified position, generating surrounding cubes as necessary
     *
     * @param cube The cube to populate
     */
    private boolean populateCube(Cube cube, Timing timing) {
        int cubeX = cube.getX();
        int cubeY = cube.getY();
        int cubeZ = cube.getZ();
        // A delivery request schedules its neighbours too; a forced one forces only the pregeneration box.
        Timing neighbours = timing == Timing.SCHEDULE ? Timing.SCHEDULE : Timing.POLL;

        // for all cubes needed for full population - generate their population requirements
        Box fullPopulation = cubeGen.getFullPopulationRequirements(cube);
        boolean useVanillaGenerators = ((ICubicWorld) worldServer).isCubicWorld()
                && CubicChunksConfig.useVanillaChunkWorldGenerators
                && cube.getY() >= 0 && cube.getY() < 16;
        if (useVanillaGenerators) {
            fullPopulation = new Box(
                    0, -cube.getY(), 0,
                    0, 16 - cube.getY() - 1, 0
            ).add(fullPopulation);
        }
        Set<Cube> newlyPopulatedCubes = new HashSet<>();
        boolean success = fullPopulation.allMatch((x, y, z) -> {
            // this also generates the cube
            Cube fullPopulationCube = getCube(x + cubeX, y + cubeY, z + cubeZ, Requirement.GENERATE, neighbours);
            Box newBox = cubeGen.getPopulationPregenerationRequirements(fullPopulationCube);
            if (useVanillaGenerators) {
                newBox = new Box(
                        0, -cube.getY(), 0,
                        0, 16 - cube.getY() - 1, 0
                ).add(newBox);
            }
            boolean generated = newBox.allMatch((nx, ny, nz) -> {
                int genX = cubeX + x + nx;
                int genY = cubeY + y + ny;
                int genZ = cubeZ + z + nz;
                return !(getCube(genX, genY, genZ, Requirement.GENERATE, timing) instanceof BlankCube);
            });
            if (!generated) {
                return false;
            }
            // a check for populators that populate more than one cube (vanilla compatibility generator)
            if (!fullPopulationCube.isPopulated()) {
                cubeGen.populate(fullPopulationCube);
                newlyPopulatedCubes.add(fullPopulationCube);
            }
            return true;
        });
        if (!success) {
            for (Cube newlyPopulatedCube : newlyPopulatedCubes) {
                newlyPopulatedCube.setPopulated(true);
            }
            return false;
        }
        if (useVanillaGenerators) {
            Box.Mutable box = fullPopulation.asMutable();
            box.setY1(0);
            box.setY2(0);
            box.forEachPoint((x, y, z) -> {
                Cube columnCube = getCube(cubeX + x, 0, cubeZ + z, Requirement.GENERATE, neighbours);
                if (!columnCube.isPopulated()) {
                    GameRegistry.generateWorld(cubeX + x, cubeZ + z, world, chunkGenerator, world.getChunkProvider());
                    columnCube.setFullyPopulated(true);
                }
            });
        }
        for (Cube newlyPopulatedCube : newlyPopulatedCubes) {
            newlyPopulatedCube.setPopulated(true);
        }
        cube.setFullyPopulated(true);
        return true;
    }

    /**
     * Initialize skylight for the cube at the specified position, generating surrounding cubes as needed.
     *
     * @param cube The cube to light up
     */
    private void calculateDiffuseSkylight(Cube cube) {
        ((ICubicWorldInternal) this.worldServer).getLightingManager().doFirstLight(cube);
        cube.setInitialLightingDone(true);
    }


    /**
     * Retrieve a column, asynchronously. The work done to retrieve the column is specified by the
     * {@link Requirement} <code>req</code>
     *
     * @param columnX Column x position
     * @param columnZ Column z position
     * @param req Work done to retrieve the column
     * @param callback Callback to be called when the column has finished loading. Note that the returned column is not
     * guaranteed to be non-null
     *
     * @see CubeProviderServer#getColumn(int, int, Requirement) for the synchronous variant of this method
     */
    public void asyncGetColumn(int columnX, int columnZ, Requirement req, Consumer<Chunk> callback) {
        asyncGetColumn(columnX, columnZ, req, callback, callback);
    }

    private void asyncGetColumn(int columnX, int columnZ, Requirement req, Consumer<Chunk> callback, Object callbackIdentity) {
        Chunk column = getLoadedColumn(columnX, columnZ);
        if (column != null || req == Requirement.GET_CACHED) {
            callback.accept(column);
            return;
        }

        AsyncWorldIOExecutor.queueColumnLoad(worldServer, cubeIO, columnX, columnZ,
                AsyncWorldIOExecutor.withCallbackIdentity(callbackIdentity, col -> {
            col = postProcessColumn(columnX, columnZ, col, req, Timing.POLL);
            callback.accept(col);
        }), loadingColumns);
    }

    @Nullable @Override
    public Chunk getColumn(int columnX, int columnZ, Requirement req) {
        return getColumn(columnX, columnZ, req, Timing.POLL);
    }

    /** A delivery request for a column; see {@link #getCubeForDelivery}. */
    @Nullable
    Chunk getColumnForDelivery(int columnX, int columnZ, Requirement req) {
        return getColumn(columnX, columnZ, req, Timing.SCHEDULE);
    }

    @Nullable
    private Chunk getColumn(int columnX, int columnZ, Requirement req, Timing timing) {
        Chunk column = getLoadedColumn(columnX, columnZ);
        if (column != null || req == Requirement.GET_CACHED) {
            return column;
        }

        column = AsyncWorldIOExecutor.syncColumnLoad(worldServer, cubeIO, columnX, columnZ, loadingColumns);
        column = postProcessColumn(columnX, columnZ, column, req, timing);

        return column;
    }

    /**
     * After loading a column, do work on it, where the work required is specified by <code>req</code>
     *
     * @param columnX X position of the column
     * @param columnZ Z position of the column
     * @param column The loaded column, or <code>null</code> if the column couldn't be loaded
     * @param req The amount of work to be done on the cube
     *
     * @return The postprocessed column, or <code>null</code>
     */
    @Nullable
    private Chunk postProcessColumn(int columnX, int columnZ, @Nullable Chunk column, Requirement req, Timing timing) {
        Chunk loaded = getLoadedColumn(columnX, columnZ);
        if (loaded != null) {
            if (column != null && loaded != column) {
                throw new IllegalStateException("Duplicate column at " + columnX + ", " + columnZ + "!");
            }
            return loaded;
        }
        if (column != null) {
            loadedChunks.put(ChunkPos.asLong(columnX, columnZ), column);
            if (maintenance != null) maintenance.columns.add(column);
            column.setLastSaveTime(this.worldServer.getTotalWorldTime()); // the column was just loaded
            column.onLoad();
            return column;
        } else if (req == Requirement.LOAD) {
            return null;
        }

        if (timing != Timing.NOW && pollColumn(columnX, columnZ, timing) != ICubeGenerator.GeneratorReadyState.READY) {
            return emptyColumn;
        }
        column = cubeGen.tryGenerateColumn(world, columnX, columnZ, new ChunkPrimer(), timing == Timing.NOW).orElse(null);
        if (column == null) {
            return emptyColumn;
        }

        loadedChunks.put(ChunkPos.asLong(columnX, columnZ), column);
        if (maintenance != null) maintenance.columns.add(column);
        column.setLastSaveTime(this.worldServer.getTotalWorldTime()); // the column was just generated
        column.onLoad();
        return column;
    }

    boolean concurrentGenerationActive() { return concurrentGeneration != null; }

    /**
     * Whether a player may arrive in a column now: it is loaded or saved, or generation workers have
     * prepared it, or there are no workers. Otherwise the column goes to the workers (once) and this
     * is false, so a teleport can wait for it instead of the server thread generating it on the spot.
     */
    public boolean arrivalReady(int columnX, int columnZ) {
        if (concurrentGeneration == null || getLoadedColumn(columnX, columnZ) != null
                || cubeIO.columnExists(columnX, columnZ)) return true;
        return concurrentGeneration.pollColumn(columnX, columnZ, true) == ICubeGenerator.GeneratorReadyState.READY;
    }

    boolean concurrentGenerationSaturated() { return concurrentGeneration != null && concurrentGeneration.saturated(); }

    public void closeConcurrentGeneration() { if (concurrentGeneration != null) concurrentGeneration.close(); }

    private ICubeGenerator.GeneratorReadyState pollCube(int cubeX, int cubeY, int cubeZ, Timing timing) {
        return concurrentGeneration == null ? cubeGen.pollAsyncCubeGenerator(cubeX, cubeY, cubeZ)
                : concurrentGeneration.pollCube(cubeX, cubeY, cubeZ, timing == Timing.SCHEDULE);
    }

    private ICubeGenerator.GeneratorReadyState pollColumn(int columnX, int columnZ, Timing timing) {
        return concurrentGeneration == null ? cubeGen.pollAsyncColumnGenerator(columnX, columnZ)
                : concurrentGeneration.pollColumn(columnX, columnZ, timing == Timing.SCHEDULE);
    }

    public String dumpLoadedCubes() {
        StringBuilder sb = new StringBuilder(10000).append("\n");
        for (Chunk chunk : this.loadedChunks.values()) {
            if (chunk == null) {
                sb.append("column = null\n");
                continue;
            }
            sb.append("Column[").append(chunk.x).append(", ").append(chunk.z).append("] {");
            boolean isFirst = true;
            for (ICube cube : ((IColumn) chunk).getLoadedCubes()) {
                if (!isFirst) {
                    sb.append(", ");
                }
                isFirst = false;
                if (cube == null) {
                    sb.append("cube = null");
                    continue;
                }
                sb.append("Cube[").append(cube.getY()).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override @Nonnull public ICubeIO getCubeIO() {
        return cubeIO;
    }

    Iterator<Cube> cubesIterator() {
        return maintenance == null ? cubeMap.iterator() : maintenance.cubes.track(cubeMap.iterator());
    }

    @SuppressWarnings("unchecked")
    Iterator<Chunk> columnsIterator() {
        return maintenance == null ? loadedChunks.values().iterator() : maintenance.columns.track(loadedChunks.values().iterator());
    }

    boolean tryUnloadCube(Cube cube) {
        if (deliveryMode.incremental()) {
            // Delivery may still hold a cube with no ticket yet (a pending load callback, or a player
            // a callback moved since reconciliation).
            if (demand().holdsCube(cube.getX(), cube.getY(), cube.getZ())
                    || !AsyncWorldIOExecutor.canDropColumn(worldServer, cube.getX(), cube.getZ())) return false;
        }
        if (ForgeChunkManager.getPersistentChunksFor(world).containsKey(cube.getColumn().getPos())) {
            return false; // it will be unloaded later by ChunkGC
        }
        if (!cube.getTickets().canUnload()) {
            return false; // There are tickets
        }

        // unload the Cube!
        cube.onUnload();

        if (cube.needsSaving()) { // save the Cube, if it needs saving
            this.cubeIO.saveCube(cube);
        }

        if (cube.getColumn().removeCube(cube.getY()) == null) {
            throw new RuntimeException();
        }
        return true;
    }

    /** What delivery asks of this provider, from the world's player chunk map. */
    private CubeDemand demand() {
        return (CubeDemand) world.getPlayerChunkMap();
    }

    boolean tryUnloadColumn(Chunk column) {
        if (ForgeChunkManager.getPersistentChunksFor(world).containsKey(column.getPos())) {
            return false; // it will be unloaded later by ChunkGC
        }
        if (((IColumn) column).hasLoadedCubes()) {
            return false; // It has loaded Cubes in it (Cubes are to Columns, as tickets are to Cubes... in a way)
        }
        // PlayerChunkMap may contain reference to a column that for a while doesn't yet have any cubes generated
        if (world.getPlayerChunkMap().contains(column.x, column.z)) {
            return false;
        }
        // ask async loader if there are currently any cubes being loaded for this column
        // this should prevent hard to debug issues with columns being unloaded while cubes have reference to them
        if (!AsyncWorldIOExecutor.canDropColumn(worldServer, column.x, column.z)) {
            return false;
        }
        column.unloadQueued = true;

        // unload the Column!
        column.onUnload();

        if (column.needsSaving(true)) { // save the Column, if it needs saving
            this.cubeIO.saveColumn(column);
        }
        return true;
    }

    public ICubeGenerator getCubeGenerator() {
        return cubeGen;
    }

    public int getLoadedCubeCount() {
        return cubeMap.getSize();
    }
}
