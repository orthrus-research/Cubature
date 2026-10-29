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

import static io.github.opencubicchunks.cubicchunks.api.util.Coords.blockToCube;
import static io.github.opencubicchunks.cubicchunks.api.util.Coords.blockToLocal;
import io.github.opencubicchunks.cubicchunks.core.util.WatchersSortingList2D;
import io.github.opencubicchunks.cubicchunks.core.util.WatchersSortingList3D;
import static net.minecraft.util.math.MathHelper.clamp;

import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.util.XYZMap;
import io.github.opencubicchunks.cubicchunks.api.util.XZMap;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.DeliveryMode;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.lighting.SurfaceHeightListener;
import io.github.opencubicchunks.cubicchunks.core.visibility.CubeSelector;
import io.github.opencubicchunks.cubicchunks.core.visibility.CuboidalCubeSelector;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * A cubic chunks implementation of Player Manager.
 * <p>
 * This class manages loading and unloading cubes for players.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class PlayerCubeMap extends PlayerChunkMap implements CubeDemand, SurfaceHeightListener, CubeSender.Watchers {
    private final GenerationBudget generationBudget = new GenerationBudget();

    private static final Predicate<EntityPlayerMP> NOT_SPECTATOR = player -> player != null && !player.isSpectator();

    /**
     * Cube selector is used to find which cube positions need to be loaded/unloaded
     * By default use CuboidalCubeSelector.
     */
    private final CubeSelector cubeSelector = new CuboidalCubeSelector();

    /**
     * Mapping if entityId to PlayerCubeMap.PlayerWrapper objects.
     */
    private final TIntObjectMap<PlayerWrapper> players = new TIntObjectHashMap<>();

    /**
     * Mapping of Cube positions to CubeWatchers (Cube equivalent of PlayerManager.PlayerInstance).
     * Contains cube positions of all cubes loaded by players.
     */
    final XYZMap<CubeWatcher> cubeWatchers = new XYZMap<>(0.7f, 25 * 25 * 25);

    /**
     * Mapping of Column positions to ColumnWatchers.
     * Contains column positions of all columns loaded by players.
     * Exists for compatibility with vanilla and to send ColumnLoad/Unload packets to clients.
     * Columns cannot be managed by client because they have separate data, like heightmap and biome array.
     */
    final XZMap<ColumnWatcher> columnWatchers = new XZMap<>(0.7f, 25 * 25);

    /**
     * All cubeWatchers that have pending block updates to send.
     */
    private final Set<CubeWatcher> cubeWatchersToUpdate = new HashSet<>();

    /**
     * All columnWatchers that have pending height updates to send.
     */
    private final Set<ColumnWatcher> columnWatchersToUpdate = new HashSet<>();

    /**
     * A queue of cubes to add a player to, this limits the amount of cubes sent to a player per tick to the set limit
     * even when joining an area with already existing cube watchers
     */
    private final WatchersSortingList3D<CubeWatcher> watchersToAddPlayersTo = new WatchersSortingList3D<>(0, () ->
            players.valueCollection().stream().map(p -> p.playerEntity).collect(Collectors.toList()));

    /**
     * Contains all CubeWatchers that need to be sent to clients,
     * but these cubes are not fully loaded/generated yet.
     * <p>
     * Note that this is not the same as cubesToGenerate list.
     * Cube can be loaded while not being fully generated yet (not in the last GeneratorStageRegistry stage).
     */
    private final WatchersSortingList3D<CubeWatcher> cubesToSendToClients = new WatchersSortingList3D<>(1, () ->
            players.valueCollection().stream().map(p -> p.playerEntity).collect(Collectors.toList()));

    /**
     * Contains all CubeWatchers that still need to be loaded/generated.
     * CubeWatcher constructor attempts to load cube from disk, but it won't generate it.
     * Technically it can generate it, using the world's IGeneratorPipeline,
     * but spectator players can't generate chunks if spectatorsGenerateChunks gamerule is set.
     */
    private final WatchersSortingList3D<CubeWatcher> cubesToGenerate = new WatchersSortingList3D<>(2, () ->
            players.valueCollection().stream().map(p -> p.playerEntity).collect(Collectors.toList()));

    /**
     * Contains all ColumnWatchers that need to be sent to clients,
     * but these cubes are not fully loaded/generated yet.
     * <p>
     * Note that this is not the same as columnsToGenerate list.
     * Columns can be loaded while not being fully generated yet
     */
    private final WatchersSortingList2D<ColumnWatcher> columnsToSendToClients = new WatchersSortingList2D<>(3, () ->
            players.valueCollection().stream().map(p -> p.playerEntity).collect(Collectors.toList()));

    /**
     * Contains all ColumnWatchers that still need to be loaded/generated.
     * ColumnWatcher constructor attempts to load column from disk, but it won't generate it.
     */
    private final WatchersSortingList2D<ColumnWatcher> columnsToGenerate = new WatchersSortingList2D<>(4, () ->
            players.valueCollection().stream().map(p -> p.playerEntity).collect(Collectors.toList()));

    private final WatchersSortingList3D<CubeWatcher> tickableCubeTracker = new WatchersSortingList3D<>(5, () ->
            players.valueCollection().stream().map(p -> p.playerEntity).filter(NOT_SPECTATOR).collect(Collectors.toList()));

    private int horizontalViewDistance;
    private int verticalViewDistance;

    /**
     * This is used only to force update of all CubeWatchers every 8000 ticks
     */
    private long previousWorldTime = 0;

    private final CubeProviderServer cubeCache;
    /** The provider's mode, so this map and the provider always run the same delivery stage. */
    private final DeliveryMode deliveryMode;

    /** Sends what watchers queue; the world creates its vanilla network handler before this map. */
    private final CubeSender sender = new CubeSender(getWorldServer(), this);

    // these player adds will be processed on the next tick
    // this exists as temporary workaround to player respawn code calling addPlayer() before spawning
    // the player in world as it's spawning player in world that triggers sending cubic chunks world
    // information to client, this causes the server to send columns to the client before the client
    // knows it's a cubic chunks world delaying addPlayer() by one tick fixes it.
    // this should be fixed by hooking into the code in a different place to send the cubic chunks world information
    // (player respawn packet?)
    private Set<EntityPlayerMP> pendingPlayerAddToCubeMap = new HashSet<>();

    private final TickableChunkContainer tickableChunksCubesToReturn = new TickableChunkContainer();

    // see comment in updateMovingPlayer() for explnation why it's in this class
    private final ChunkGc chunkGc;

    private final DeferredArrivals arrivals;

    final VanillaNetworkHandler vanillaNetworkHandler;

    public PlayerCubeMap(WorldServer worldServer) {
        super(worldServer);
        this.cubeCache = ((ICubicWorldInternal.Server) worldServer).getCubeCache();
        this.deliveryMode = cubeCache.getDeliveryMode();
        this.setPlayerViewDistance(worldServer.getMinecraftServer().getPlayerList().getViewDistance(),
                ((ICubicPlayerList) worldServer.getMinecraftServer().getPlayerList()).getVerticalViewDistance());
        this.chunkGc = new ChunkGc(((ICubicWorldInternal.Server) worldServer).getCubeCache());
        this.arrivals = new DeferredArrivals(worldServer, cubeCache::arrivalReady);
        this.vanillaNetworkHandler = ((ICubicWorldInternal.Server) worldServer).getVanillaNetworkHandler();
    }

    /**
     * This method exists only because vanilla needs it. It shouldn't be used anywhere else.
     */
    @Override
    @Deprecated // Warning: Hacks! For vanilla use only! (WorldServer.updateBlocks())
    public Iterator<Chunk> getChunkIterator() {
        // CubicChunks.bigWarning("Usage of PlayerCubeMap#getChunkIterator detected in a cubic chunks world! "
        //        + "This is likely to work incorrectly. This is not supported.");
        // TODO: throw UnsupportedOperationException?
        Iterator<Chunk> chunkIt = this.cubeCache.getLoadedChunks().iterator();
        return new AbstractIterator<Chunk>() {
            @Override protected Chunk computeNext() {
                while (chunkIt.hasNext()) {
                    IColumn column = (IColumn) chunkIt.next();
                    if (column.shouldTick()) { // shouldTick is true when there Cubes with tickets the request to be ticked
                        return (Chunk) column;
                    }
                }
                return this.endOfData();
            }
        };
    }

    public TickableChunkContainer getTickableChunks() {
        TickableChunkContainer tickableChunksCubes = this.tickableChunksCubesToReturn;
        tickableChunksCubes.clear();
        addTickableColumns(tickableChunksCubes);
        addTickableCubes(tickableChunksCubes);
        addForcedColumns(tickableChunksCubes);
        addForcedCubes(tickableChunksCubes);
        return tickableChunksCubes;
    }

    private void addForcedColumns(TickableChunkContainer tickableChunksCubes) {
        for(IColumn columns : ((ICubicWorldInternal.Server) getWorldServer()).getForcedColumns()) {
            tickableChunksCubes.addColumn((Chunk) columns);
        }
    }

    private void addForcedCubes(TickableChunkContainer tickableChunksCubes) {
        tickableChunksCubes.forcedCubes = ((ICubicWorldInternal.Server) getWorldServer()).getForcedCubes();
    }

    private void addTickableCubes(TickableChunkContainer tickableChunksCubes) {
        for (CubeWatcher watcher : (Iterable<CubeWatcher>) () -> tickableCubeTracker.iteratorUpToDistance(9)) {
            ICube cube = watcher.getCube();
            if (cube == null || !watcher.hasPlayerMatchingInRange(NOT_SPECTATOR, 128)) {
                continue;
            }
            tickableChunksCubes.addCube(cube);
        }
    }

    private void addTickableColumns(TickableChunkContainer tickableChunksCubes) {
        for (ColumnWatcher watcher : columnWatchers) {
            Chunk chunk = watcher.getChunk();
            if (chunk == null || !watcher.hasPlayerMatchingInRange(128.0D, NOT_SPECTATOR)) {
                continue;
            }
            tickableChunksCubes.addColumn(chunk);
        }
    }

    /**
     * Updates all CubeWatchers and ColumnWatchers.
     * Also sends packets to clients.
     */
    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public void tick() {
        getWorldServer().profiler.startSection("playerCubeMapTick");
        boolean spectatorsGenerateChunks = getWorldServer().getGameRules().getBoolean("spectatorsGenerateChunks");
        Predicate<EntityPlayerMP> canGenerateChunkPredicate = player -> player != null && (spectatorsGenerateChunks ||!player.isSpectator());

        long currentTime = this.getWorldServer().getTotalWorldTime();

        arrivals.tick(currentTime);

        getWorldServer().profiler.startSection("addPendingPlayers");
        addPendingPlayers();
        getWorldServer().profiler.endStartSection("tickEntries");
        updateWatchers(currentTime);
        getWorldServer().profiler.endStartSection("sortTickableTracker");
        tickableCubeTracker.tick();

        getWorldServer().profiler.endStartSection("sortToGenerate");
        this.cubesToGenerate.tick();
        this.columnsToGenerate.tick();

        getWorldServer().profiler.endStartSection("sortToSend");
        this.cubesToSendToClients.tick();
        this.columnsToSendToClients.tick();
        this.watchersToAddPlayersTo.tick();

        getWorldServer().profiler.endStartSection("generate");
        boolean concurrent = cubeCache.concurrentGenerationActive();
        generationBudget.reset(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(concurrent
                        ? CubicChunksConfig.concurrentGenerationTickMillis : CubicChunksConfig.maxCubeGenerationTimeMillis),
                CubicChunksConfig.maxGeneratedCubesPerTick);
        // Watchers make delivery requests: generation goes to the workers and is installed when ready.
        generateColumns(canGenerateChunkPredicate);
        generateCubes(canGenerateChunkPredicate, concurrent);
        getWorldServer().profiler.endStartSection("send");
        sendColumnsToClients();
        sendCubesToClients();
        addPlayersToWatchers();
        getWorldServer().profiler.endStartSection("unload");
        unloadIfNoPlayers();
        getWorldServer().profiler.endStartSection("sendCubes");//unload
        sender.send();
        if (deliveryMode.prefetch()) prepareAhead(canGenerateChunkPredicate, currentTime);
        if (deliveryMode.incremental()) maintain();
        getWorldServer().profiler.endSection();//sendCubes
        getWorldServer().profiler.endSection();//playerCubeMapTick
    }

    private void addPendingPlayers() {
        if (!pendingPlayerAddToCubeMap.isEmpty()) {
            // copy in case player still isn't in world
            Set<EntityPlayerMP> players = pendingPlayerAddToCubeMap;
            pendingPlayerAddToCubeMap = new HashSet<>();
            for (EntityPlayerMP player : players) {
                addPlayer(player);
            }
        }
    }

    /** Updates every cube watcher every 8000 ticks, and each watcher queued for an update. */
    private void updateWatchers(long currentTime) {
        //force update-all every 8000 ticks (400 seconds)
        if (currentTime - this.previousWorldTime > 8000L) {
            this.previousWorldTime = currentTime;

            for (CubeWatcher playerInstance : this.cubeWatchers) {
                playerInstance.update();
                playerInstance.updateInhabitedTime();
            }
        }


        //process instances to update
        if (!cubeWatchersToUpdate.isEmpty()) {
            this.cubeWatchersToUpdate.forEach(CubeWatcher::update);
            this.cubeWatchersToUpdate.clear();
        }

        if (!columnWatchersToUpdate.isEmpty()) {
            this.columnWatchersToUpdate.forEach(ColumnWatcher::update);
            this.columnWatchersToUpdate.clear();
        }
    }

    private void generateColumns(Predicate<EntityPlayerMP> canGenerateChunkPredicate) {
        if (!this.columnsToGenerate.isEmpty()) {
            getWorldServer().profiler.startSection("columns");
            Iterator<ColumnWatcher> iter = this.columnsToGenerate.iterator();
            while (iter.hasNext() && generationBudget.hasTime(System.nanoTime())) {
                ColumnWatcher entry = iter.next();

                boolean success = entry.getChunk() != null;
                if (!success) {
                    boolean canGenerate = entry.hasPlayerMatching(canGenerateChunkPredicate);
                    getWorldServer().profiler.startSection("generate");
                    success = entry.providePlayerChunk(canGenerate);
                    getWorldServer().profiler.endSection(); // generate
                }

                if (success) {
                    iter.remove();

                    if (entry.sendToPlayers()) {
                        this.columnsToSendToClients.remove(entry);
                    }
                }
            }

            getWorldServer().profiler.endSection(); // columns
        }
    }

    /** Generates or installs queued cubes within the generation budget, then offers them to players. */
    private void generateCubes(Predicate<EntityPlayerMP> canGenerateChunkPredicate, boolean concurrent) {
        if (!this.cubesToGenerate.isEmpty()) {
            getWorldServer().profiler.startSection("cubes");

            Iterator<CubeWatcher> iterator = this.cubesToGenerate.iterator();
            int saturatedWaits = 0;

            while (iterator.hasNext() && generationBudget.canGenerateCube(System.nanoTime())) {
                CubeWatcher watcher = iterator.next();
                if (watcher.isWaitingForColumn()) {
                    continue;
                }
                boolean success = !watcher.isWaitingForCube();
                boolean alreadyLoaded = success;
                if (!success) {
                    boolean canGenerate = watcher.hasPlayerMatching(canGenerateChunkPredicate);
                    getWorldServer().profiler.startSection("generate");
                    success = watcher.providePlayerCube(canGenerate);
                    getWorldServer().profiler.endSection();
                }
                // With every worker slot taken, further polls only wait; installs resume next tick.
                if (!success && concurrent && cubeCache.concurrentGenerationSaturated() && ++saturatedWaits > 256) {
                    break;
                }

                if (success) {
                    CubeWatcher.SendToPlayersResult state = watcher.sendToPlayers();
                    if (state == CubeWatcher.SendToPlayersResult.WAITING || state == CubeWatcher.SendToPlayersResult.CUBE_SENT
                            || state == CubeWatcher.SendToPlayersResult.ALREADY_DONE) {
                        iterator.remove();
                        this.cubesToSendToClients.remove(watcher);
                    }
                    if (!alreadyLoaded) {
                        generationBudget.generatedCube();
                    }
                }
            }

            getWorldServer().profiler.endSection(); // chunks
        }
    }

    private void sendColumnsToClients() {
        if (!this.columnsToSendToClients.isEmpty()) {
            getWorldServer().profiler.startSection("columns");

            Iterator<ColumnWatcher> it = this.columnsToSendToClients.iterator();

            while (it.hasNext()) {
                ColumnWatcher playerInstance = it.next();

                if (playerInstance.sendToPlayers()) {
                    it.remove();
                } else if (!columnsToGenerate.contains(playerInstance)) {
                    columnsToGenerate.add(playerInstance);
                }
            }
            getWorldServer().profiler.endSection(); // columns
        }
    }

    private void sendCubesToClients() {
        if (!this.cubesToSendToClients.isEmpty()) {
            getWorldServer().profiler.startSection("cubes");
            int toSend = CubicChunksConfig.cubesToSendPerTick;
            Iterator<CubeWatcher> it = this.cubesToSendToClients.iterator();

            while (it.hasNext() && toSend > 0) {
                CubeWatcher playerInstance = it.next();

                CubeWatcher.SendToPlayersResult state = playerInstance.sendToPlayers();
                if (state == CubeWatcher.SendToPlayersResult.ALREADY_DONE || state == CubeWatcher.SendToPlayersResult.CUBE_SENT) {
                    it.remove();
                    --toSend;
                }
            }
            getWorldServer().profiler.endSection(); // cubes
        }
    }

    /** Adds scheduled players to their watchers, cubesToSendPerTick at a time. */
    private void addPlayersToWatchers() {
        if (!watchersToAddPlayersTo.isEmpty()) {
            int toSend = CubicChunksConfig.cubesToSendPerTick;
            for (Iterator<CubeWatcher> iter = watchersToAddPlayersTo.iterator(); toSend > 0 && iter.hasNext(); ) {
                CubeWatcher watcher = iter.next();
                watcher.addScheduledPlayers();
                CubeWatcher.SendToPlayersResult state = watcher.sendToPlayers();
                if (state == CubeWatcher.SendToPlayersResult.WAITING) {
                    if (!cubesToGenerate.contains(watcher)) {
                        cubesToGenerate.add(watcher);
                    }
                }
                if (state != CubeWatcher.SendToPlayersResult.ALREADY_DONE) {
                    toSend--;
                }
                iter.remove();
            }
        }
    }

    private void unloadIfNoPlayers() {
        //if there are no players - unload everything
        if (this.players.isEmpty()) {
            WorldProvider worldprovider = this.getWorldServer().provider;

            if (!worldprovider.canRespawnHere()) {
                this.getWorldServer().getChunkProvider().queueUnloadAll();
            }
        }
    }

    /** PREFETCH: each player's ahead preparation, starting with a different player each tick. */
    private void prepareAhead(Predicate<EntityPlayerMP> canGenerateChunkPredicate, long currentTime) {
        PlayerWrapper[] candidates=players.valueCollection().toArray(new PlayerWrapper[0]);
        for(int i=0;i<candidates.length;i++) {
            PlayerWrapper player=candidates[(i+(int)Math.floorMod(currentTime,(long)candidates.length))%candidates.length];
            if(!canGenerateChunkPredicate.apply(player.playerEntity)){player.ahead.close();continue;}
            player.ahead.update(player.playerEntity,horizontalViewDistance,verticalViewDistance,currentTime);
            player.ahead.service(cubeCache,generationBudget,64);
        }
    }

    /** Incremental maintenance: reconcile every player's view, then collect, once per world tick. */
    private void maintain() {
        // Collection follows demand reconciliation on the world owner. It is serviced once
        // per world tick, independent of player speed and whether players are moving at all.
        for (PlayerWrapper player : players.valueCollection().toArray(new PlayerWrapper[0]))
            updateMovingPlayer(player.playerEntity);
        this.chunkGc.tick();
    }

    /** Teleports held while generation workers prepare their destination column. */
    public DeferredArrivals arrivals() { return arrivals; }

    CubeProviderServer cubeCache() { return cubeCache; }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public boolean contains(int cubeX, int cubeZ) {
        return this.columnWatchers.get(cubeX, cubeZ) != null;
    }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public PlayerChunkMapEntry getEntry(int cubeX, int cubeZ) {
        return this.columnWatchers.get(cubeX, cubeZ);
    }

    /**
     * Returns existing CubeWatcher or creates new one if it doesn't exist.
     * Attempts to load the cube and send it to client.
     * If it can't load it or send it to client - adds it to cubesToGenerate/cubesToSendToClients
     */
    private CubeWatcher getOrCreateCubeWatcher(@Nonnull CubePos cubePos) {
        CubeWatcher cubeWatcher = this.cubeWatchers.get(cubePos.getX(), cubePos.getY(), cubePos.getZ());

        if (cubeWatcher == null) {
            // make a new watcher
            cubeWatcher = new CubeWatcher(this, cubePos);
            this.cubeWatchers.put(cubeWatcher);
            this.tickableCubeTracker.add(cubeWatcher);


            if (cubeWatcher.isWaitingForColumn() || cubeWatcher.isWaitingForCube()) {
                this.cubesToGenerate.add(cubeWatcher);
            }
            // vanilla has the below check, which causes the cubes to be sent to client too early and sometimes in too big amounts
            // if they are sent too early, client won't have the right player position and renderer positions are wrong
            // which cause some cubes to not be rendered
            // DO NOT make it the same as vanilla until it's confirmed that Mojang fixed MC-120079
            //if (!cubeWatcher.sendToPlayers()) {
                this.cubesToSendToClients.add(cubeWatcher);
            //}
        }
        return cubeWatcher;
    }

    /**
     * Returns existing ColumnWatcher or creates new one if it doesn't exist.
     * Always creates the Column.
     */
    private ColumnWatcher getOrCreateColumnWatcher(ChunkPos chunkPos) {
        ColumnWatcher columnWatcher = this.columnWatchers.get(chunkPos.x, chunkPos.z);
        if (columnWatcher == null) {
            columnWatcher = new ColumnWatcher(this, chunkPos);
            this.columnWatchers.put(columnWatcher);
            if (columnWatcher.getChunk() == null) {
                this.columnsToGenerate.add(columnWatcher);
            }
            if (!columnWatcher.sendToPlayers()) {
                this.columnsToSendToClients.add(columnWatcher);
            }
        }
        return columnWatcher;
    }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public void markBlockForUpdate(BlockPos pos) {
        CubeWatcher cubeWatcher = this.getCubeWatcher(CubePos.fromBlockCoords(pos));

        if (cubeWatcher != null) {
            int localX = blockToLocal(pos.getX());
            int localY = blockToLocal(pos.getY());
            int localZ = blockToLocal(pos.getZ());
            cubeWatcher.blockChanged(localX, localY, localZ);
        }
    }

    @Override
    public void heightUpdated(int blockX, int blockZ) {
        ColumnWatcher columnWatcher = this.columnWatchers.get(blockToCube(blockX), blockToCube(blockZ));
        if (columnWatcher != null) {
            int localX = blockToLocal(blockX);
            int localZ = blockToLocal(blockZ);
            columnWatcher.heightChanged(localX, localZ);
        }
    }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public void addPlayer(EntityPlayerMP player) {
        if (player.world != this.getWorldServer()) {
            CubicChunks.bigWarning("Player world not the same ad PlayerCubeMap world! Adding anyway. This is very likely to cause issues! Player "
                            + "world dimension ID: %d, PlayerCubeMap dimension ID: %d", player.world.provider.getDimension(),
                    getWorldServer().provider.getDimension());
        } else if (!player.world.playerEntities.contains(player)) {
            CubicChunks.LOGGER.debug("PlayerCubeMap (dimension {}): Adding player to pending to add list", getWorldServer().provider.getDimension());
            pendingPlayerAddToCubeMap.add(player);
            return;
        }

        PlayerWrapper playerWrapper = new PlayerWrapper(player);
        playerWrapper.updateManagedPos();

        if (!vanillaNetworkHandler.hasCubicChunks(player)) {
            vanillaNetworkHandler.updatePlayerPosition(this, player, playerWrapper.getManagedCubePos());
        }

        CubePos playerCubePos = CubePos.fromEntity(player);

        this.cubeSelector.forAllVisibleFrom(playerCubePos, horizontalViewDistance, verticalViewDistance, (currentPos) -> {
            //create cubeWatcher and chunkWatcher
            //order is important
            ColumnWatcher chunkWatcher = getOrCreateColumnWatcher(currentPos.chunkPos());
            //and add the player to them
            if (!chunkWatcher.containsPlayer(player)) {
                chunkWatcher.addPlayer(player);
            }
            CubeWatcher cubeWatcher = getOrCreateCubeWatcher(currentPos);

            scheduleAddPlayerToWatcher(cubeWatcher, player);
        });
        this.players.put(player.getEntityId(), playerWrapper);
    }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public void removePlayer(EntityPlayerMP player) {
        PlayerWrapper playerWrapper = this.players.get(player.getEntityId());
        if (playerWrapper == null) {
            return;
        }
        playerWrapper.ahead.close();
        // Minecraft does something evil there: this method is called *after* changing the player's position
        // so we need to use managedPosition there
        CubePos playerCubePos = CubePos.fromEntityCoords(player.managedPosX, playerWrapper.managedPosY, player.managedPosZ);

        // send unload columns later so that they get unloaded after their corresponding cubes
        ObjectSet<ColumnWatcher> toSendUnload = new ObjectOpenHashSet<>((horizontalViewDistance*2+1) * (horizontalViewDistance*2+1) * 6);
        this.cubeSelector.forAllVisibleFrom(playerCubePos, horizontalViewDistance, verticalViewDistance, (cubePos) -> {

            // get the watcher
            CubeWatcher watcher = getCubeWatcher(cubePos);
            if (watcher != null) {
                // remove from the watcher, it also removes the watcher if it becomes empty
                removePlayerFromCubeWatcher(watcher, player);
            }

            // remove column watchers if needed
            ColumnWatcher columnWatcher = getColumnWatcher(cubePos.chunkPos());
            if (columnWatcher == null) {
                return;
            }

            toSendUnload.add(columnWatcher);
        });
        toSendUnload.stream()
                .filter(watcher->watcher.containsPlayer(player))
                .forEach(watcher->watcher.removePlayer(player));
        this.players.remove(player.getEntityId());
        sender.removePlayer(player);
        vanillaNetworkHandler.removePlayer(player);
    }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public void updateMovingPlayer(EntityPlayerMP player) {
        // the player moved
        // if the player moved into a new chunk, update which chunks the player needs to know about
        // then update the list of chunks that need to be sent to the client

        // get the player info
        PlayerWrapper playerWrapper = this.players.get(player.getEntityId());

        if (playerWrapper == null) {
            // vanilla sometimes does it, this is normal
            return;
        }
        // did the player move into new cube?
        if (!playerWrapper.cubePosChanged()) {
            return;
        }

        this.updatePlayer(playerWrapper, playerWrapper.getManagedCubePos(), CubePos.fromEntity(player));
        playerWrapper.updateManagedPos();

        if (!vanillaNetworkHandler.hasCubicChunks(player)) {
            vanillaNetworkHandler.updatePlayerPosition(this, player, playerWrapper.getManagedCubePos());
        }
        // With ChunkGc being separate from PlayerCubeMap, there are 2 issues:
        // Problem 0: Sometimes, a chunk can be generated after CubeWatcher's chunk load callback returns with a null
        // but before ChunkGC call. This means that the cube will get unloaded, even when ChunkWatcher is waiting for it.
        // Problem 1: When chunkGc call is not in this method, sometimes, when a player teleports far away and is
        // unlucky, and ChunkGc runs in the same tick the teleport appears to happen after PlayerCubeMap call, but
        // before ChunkGc call. This means that PlayerCubeMap won't yet have a CubeWatcher for the player cubes at all,
        // so even directly checking for CubeWatchers before unload attempt won't work.
        //
        // While normally not an issue as it will be reloaded soon anyway, it breaks a lot of things if that cube
        // contains the player. Which is not unlikely if the player is what caused generating this cube in the first place
        // for problem #0.
        // So we put ChunkGc here so that we can be sure it has consistent data about player location, and that no chunks are
        // loaded while we aren't looking.
        if (!deliveryMode.incremental()) this.chunkGc.tick();
    }

    @Override
    public boolean holdsCube(int x, int y, int z) {
        if (cubeWatchers.get(x, y, z) != null) return true;
        for (PlayerWrapper player : players.valueCollection())
            if (withinDeliveryView(player.playerEntity, x, y, z)) return true;
        for (EntityPlayerMP player : pendingPlayerAddToCubeMap)
            if (withinDeliveryView(player, x, y, z)) return true;
        return false;
    }

    private boolean withinDeliveryView(EntityPlayerMP player, int x, int y, int z) {
        return Math.abs((long)x - blockToCube(player.posX)) <= horizontalViewDistance
                && Math.abs((long)y - blockToCube(player.posY)) <= verticalViewDistance
                && Math.abs((long)z - blockToCube(player.posZ)) <= horizontalViewDistance;
    }

    private void updatePlayer(PlayerWrapper entry, CubePos oldPos, CubePos newPos) {
        getWorldServer().profiler.startSection("updateMovedPlayer");
        Set<CubePos> cubesToRemove = new HashSet<>();
        Set<CubePos> cubesToLoad = new HashSet<>();
        Set<ChunkPos> columnsToRemove = new HashSet<>();
        Set<ChunkPos> columnsToLoad = new HashSet<>();

        getWorldServer().profiler.startSection("findChanges");
        // calculate new visibility
        this.cubeSelector.findChanged(oldPos, newPos, horizontalViewDistance, verticalViewDistance, cubesToRemove, cubesToLoad, columnsToRemove,
                columnsToLoad);

        getWorldServer().profiler.endStartSection("createColumns");
        //order is important, columns first
        columnsToLoad.forEach(pos -> {
            ColumnWatcher columnWatcher = this.getOrCreateColumnWatcher(pos);
            assert columnWatcher.getPos().equals(pos);
            columnWatcher.addPlayer(entry.playerEntity);
        });
        getWorldServer().profiler.endStartSection("createCubes");
        cubesToLoad.forEach(pos -> {
            CubeWatcher cubeWatcher = this.getOrCreateCubeWatcher(pos);
            assert cubeWatcher.getCubePos().equals(pos);
            scheduleAddPlayerToWatcher(cubeWatcher, entry.playerEntity);
        });
        getWorldServer().profiler.endStartSection("removeCubes");
        cubesToRemove.forEach(pos -> {
            CubeWatcher cubeWatcher = this.getCubeWatcher(pos);
            if (cubeWatcher != null) {
                assert cubeWatcher.getCubePos().equals(pos);
                removePlayerFromCubeWatcher(cubeWatcher, entry.playerEntity);
            }
        });
        getWorldServer().profiler.endStartSection("removeColumns");
        columnsToRemove.forEach(pos -> {
            ColumnWatcher columnWatcher = this.getColumnWatcher(pos);
            if (columnWatcher != null) {
                assert columnWatcher.getPos().equals(pos);
                columnWatcher.removePlayer(entry.playerEntity);
            }
        });
        getWorldServer().profiler.endSection();//removeColumns
        getWorldServer().profiler.endSection();//updateMovedPlayer
    }

    private void removePlayerFromCubeWatcher(CubeWatcher cubeWatcher, EntityPlayerMP playerEntity) {
        cubeWatcher.removePlayer(playerEntity);
    }

    private void scheduleAddPlayerToWatcher(CubeWatcher cubeWatcher, EntityPlayerMP playerEntity) {
        watchersToAddPlayersTo.add(cubeWatcher);
        cubeWatcher.scheduleAddPlayer(playerEntity);
    }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    public boolean isPlayerWatchingChunk(EntityPlayerMP player, int cubeX, int cubeZ) {
        ColumnWatcher columnWatcher = this.getColumnWatcher(new ChunkPos(cubeX, cubeZ));
        return columnWatcher != null &&
                columnWatcher.containsPlayer(player) &&
                columnWatcher.isSentToPlayers();
    }

    public boolean isPlayerWatchingCube(EntityPlayerMP player, int cubeX, int cubeY, int cubeZ) {
        CubeWatcher watcher = this.getCubeWatcher(new CubePos(cubeX, cubeY, cubeZ));
        return watcher != null &&
                watcher.containsPlayer(player) &&
                watcher.isSentToPlayer(player);
    }

    // CHECKED: 1.10.2-12.18.1.2092
    @Override
    @Deprecated
    public final void setPlayerViewRadius(int newHorizontalViewDistance) {
        this.setPlayerViewDistance(newHorizontalViewDistance, verticalViewDistance);
    }

    public final void setPlayerViewDistance(int newHorizontalViewDistance, int newVerticalViewDistance) {
        //this method is called by vanilla before these fields are initialized.
        //and it doesn't really need to be called because in this case
        //it reduces to setting the viewRadius field
        if (this.players == null) {
            return;
        }

        newHorizontalViewDistance = clamp(newHorizontalViewDistance, 3, CubicChunks.hasOptifine() ? 64 : 32);
        newVerticalViewDistance = clamp(newVerticalViewDistance, 3, CubicChunks.hasOptifine() ? 64 : 32);

        if (newHorizontalViewDistance == this.horizontalViewDistance && newVerticalViewDistance == this.verticalViewDistance) {
            return;
        }
        int oldHorizontalViewDistance = this.horizontalViewDistance;
        int oldVerticalViewDistance = this.verticalViewDistance;

        // Somehow the view distances went in opposite directions
        if ((newHorizontalViewDistance < oldHorizontalViewDistance && newVerticalViewDistance > oldVerticalViewDistance) ||
                (newHorizontalViewDistance > oldHorizontalViewDistance && newVerticalViewDistance < oldVerticalViewDistance)) {
            // Adjust the values separately to avoid imploding
            setPlayerViewDistance(newHorizontalViewDistance, oldVerticalViewDistance);
            setPlayerViewDistance(newHorizontalViewDistance, newVerticalViewDistance);
            return;
        }

        for (PlayerWrapper playerWrapper : this.players.valueCollection()) {

            EntityPlayerMP player = playerWrapper.playerEntity;
            CubePos playerPos = playerWrapper.getManagedCubePos();

            if (newHorizontalViewDistance > oldHorizontalViewDistance || newVerticalViewDistance > oldVerticalViewDistance) {
                //if newRadius is bigger, we only need to load new cubes
                this.cubeSelector.forAllVisibleFrom(playerPos, newHorizontalViewDistance, newVerticalViewDistance, pos -> {
                    //order is important
                    ColumnWatcher columnWatcher = this.getOrCreateColumnWatcher(pos.chunkPos());
                    if (!columnWatcher.containsPlayer(player)) {
                        columnWatcher.addPlayer(player);
                    }
                    CubeWatcher cubeWatcher = this.getOrCreateCubeWatcher(pos);
                    if (!cubeWatcher.containsPlayer(player)) {
                        scheduleAddPlayerToWatcher(cubeWatcher, player);
                    }
                });
                // either both got smaller or only one of them changed
            } else {
                //if it got smaller...
                Set<CubePos> cubesToUnload = new HashSet<>();
                Set<ChunkPos> columnsToUnload = new HashSet<>();
                this.cubeSelector.findAllUnloadedOnViewDistanceDecrease(playerPos,
                        oldHorizontalViewDistance, newHorizontalViewDistance,
                        oldVerticalViewDistance, newVerticalViewDistance, cubesToUnload, columnsToUnload);

                cubesToUnload.forEach(pos -> {
                    CubeWatcher cubeWatcher = this.getCubeWatcher(pos);
                    if (cubeWatcher != null) {
                        removePlayerFromCubeWatcher(cubeWatcher, player);
                    } else {
                        CubicChunks.LOGGER.warn("cubeWatcher null on render distance change");
                    }
                });
                columnsToUnload.forEach(pos -> {
                    ColumnWatcher columnWatcher = this.getColumnWatcher(pos);
                    if (columnWatcher != null && columnWatcher.containsPlayer(player)) {
                        columnWatcher.removePlayer(player);
                    } else {
                        CubicChunks.LOGGER.warn("cubeWatcher null or doesn't contain player on render distance change");
                    }
                });
            }
        }

        this.horizontalViewDistance = newHorizontalViewDistance;
        this.verticalViewDistance = newVerticalViewDistance;
    }

    @Override
    public void entryChanged(PlayerChunkMapEntry entry) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeEntry(PlayerChunkMapEntry entry) {
        throw new UnsupportedOperationException();
    }

    void addToUpdateEntry(CubeWatcher cubeWatcher) {
        this.cubeWatchersToUpdate.add(cubeWatcher);
    }

    void addToUpdateEntry(ColumnWatcher columnWatcher) {
        this.columnWatchersToUpdate.add(columnWatcher);
    }

    // CHECKED: 1.10.2-12.18.1.2092
    void removeEntry(CubeWatcher cubeWatcher) {
        watchersToAddPlayersTo.remove(cubeWatcher);
        cubeWatcher.invalidate();
        CubePos cubePos = cubeWatcher.getCubePos();
        cubeWatcher.updateInhabitedTime();
        this.tickableCubeTracker.remove(cubeWatcher);
        CubeWatcher removed = this.cubeWatchers.remove(cubePos.getX(), cubePos.getY(), cubePos.getZ());
        assert removed == cubeWatcher : "Removed unexpected cube watcher";
        this.cubeWatchersToUpdate.remove(cubeWatcher);
        this.cubesToGenerate.remove(cubeWatcher);
        this.cubesToSendToClients.remove(cubeWatcher);
        if (cubeWatcher.getCube() != null) {
            cubeWatcher.getCube().getTickets().remove(cubeWatcher); // remove the ticket, so this Cube can unload
        }
        //don't unload, ChunkGc unloads chunks
    }

    public void removeEntry(ColumnWatcher entry) {
        ChunkPos pos = entry.getPos();
        entry.updateChunkInhabitedTime();
        this.columnWatchers.remove(pos.x, pos.z);
        this.columnsToGenerate.remove(entry);
        this.columnsToSendToClients.remove(entry);
        this.columnWatchersToUpdate.remove(entry);
    }

    public void scheduleSendCubeToPlayer(Cube cube, EntityPlayerMP player) {
        sender.schedule(cube, player);
    }

    public void removeSchedulesSendCubeToPlayer(Cube cube, EntityPlayerMP player) {
        sender.unschedule(cube, player);
    }

    @Override
    public boolean tracks(EntityPlayerMP player) {
        PlayerWrapper owner = players.get(player.getEntityId());
        return owner != null && owner.playerEntity == player;
    }

    @Nullable public CubeWatcher getCubeWatcher(CubePos pos) {
        return this.cubeWatchers.get(pos.getX(), pos.getY(), pos.getZ());
    }

    @Nullable public ColumnWatcher getColumnWatcher(ChunkPos pos) {
        return this.columnWatchers.get(pos.x, pos.z);
    }

    public boolean contains(CubePos coords) {
        return this.cubeWatchers.get(coords.getX(), coords.getY(), coords.getZ()) != null;
    }

    private static final class PlayerWrapper {

        final EntityPlayerMP playerEntity;
        final AheadCubePreparation ahead;
        private double managedPosY;

        PlayerWrapper(EntityPlayerMP player) {
            this.playerEntity = player;
            this.ahead = new AheadCubePreparation(player);
        }

        void updateManagedPos() {
            this.playerEntity.managedPosX = playerEntity.posX;
            this.managedPosY = playerEntity.posY;
            this.playerEntity.managedPosZ = playerEntity.posZ;
        }

        int getManagedCubePosX() {
            return blockToCube(this.playerEntity.managedPosX);
        }

        int getManagedCubePosY() {
            return blockToCube(this.managedPosY);
        }

        int getManagedCubePosZ() {
            return blockToCube(this.playerEntity.managedPosZ);
        }


        CubePos getManagedCubePos() {
            return new CubePos(getManagedCubePosX(), getManagedCubePosY(), getManagedCubePosZ());
        }

        boolean cubePosChanged() {
            // did the player move far enough to matter?
            return blockToCube(playerEntity.posX) != this.getManagedCubePosX()
                    || blockToCube(playerEntity.posY) != this.getManagedCubePosY()
                    || blockToCube(playerEntity.posZ) != this.getManagedCubePosZ();
        }
    }

    /**
     * Return iterator over 'CubeWatchers' of all cubes loaded
     * by players. Iterator first element defined by seed.
     * 
     * @param seed seed for random iterator
     * @return cube watcher iterator
     */
    public Iterator<CubeWatcher> getRandomWrappedCubeWatcherIterator(int seed) {
        return this.cubeWatchers.randomWrappedIterator(seed);
    }
    
    @Override
    public Iterator<Cube> getCubeIterator() {
        WorldServer world = this.getWorldServer();
        final Iterator<CubeWatcher> iterator = this.tickableCubeTracker.iterator();
        ImmutableSetMultimap<ChunkPos, Ticket> persistentChunksFor = ForgeChunkManager.getPersistentChunksFor(world);
        world.profiler.startSection("forcedChunkLoading");
        @SuppressWarnings("unchecked")
        final Iterator<Cube> persistentCubesIterator = persistentChunksFor.keys().stream()
                .filter(Objects::nonNull)
                .map(input -> (Collection<Cube>) ((IColumn) world.getChunk(input.x, input.z)).getLoadedCubes())
                .collect(ArrayList<Cube>::new, ArrayList::addAll, ArrayList::addAll)
                .iterator();
        world.profiler.endSection();
        
        return new AbstractIterator<Cube>() {

            Iterator<Cube> persistentCubes = persistentCubesIterator;
            
            boolean shouldSkip(@Nullable Cube cube){
                if (cube == null) 
                    return true;
                if (cube.isEmpty())
                    return true;
                if (!cube.isFullyPopulated())
                    return true;
                return false;
            }

            @Override protected Cube computeNext() {
                while(persistentCubes != null && persistentCubes.hasNext()){
                    Cube cube = persistentCubes.next();
                    if (!persistentCubes.hasNext()) {
                        persistentCubes = null;
                    }
                    if(shouldSkip(cube))
                        continue;
                    return cube;
                }
                
                while (iterator.hasNext()) {
                    CubeWatcher watcher = iterator.next();
                    Cube cube = watcher.getCube();
                    if(shouldSkip(cube))
                        continue;
                    if(!watcher.hasPlayerMatchingInRange(NOT_SPECTATOR, 128))
                        continue;
                    return cube;
                }
                return this.endOfData();
            }
        };
    }

    public static class TickableChunkContainer {

        private final ObjectArrayList<ICube> cubes = ObjectArrayList.wrap(new ICube[64*1024]);
        private XYZMap<ICube> forcedCubes;
        private final Set<Chunk> columns = Collections.newSetFromMap(new IdentityHashMap<>());

        private void clear() {
            this.cubes.clear();
            this.columns.clear();
        }

        private void addCube(ICube cube) {
            cubes.add(cube);
        }

        public void addColumn(Chunk column) {
            columns.add(column);
        }

        public Iterable<ICube> forcedCubes() {
            return forcedCubes;
        }

        public ICube[] playerTickableCubes() {
            return cubes.elements();
        }

        public Iterable<Chunk> columns() {
            return columns;
        }
    }
}
