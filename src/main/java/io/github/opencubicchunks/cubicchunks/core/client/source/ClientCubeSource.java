/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
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
package io.github.opencubicchunks.cubicchunks.core.client.source;

import io.github.opencubicchunks.cubicchunks.api.client.IClientPreviewWork;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;
import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.IHeightMap;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.client.CubeProviderClient;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;

/**
 * The cube source on the client (docs/CUBE-SOURCE.md). It turns every route by which a cube on the
 * client changes into queued events, delivers them once per client tick, and takes snapshots.
 *
 * <p>Everything here runs on the client thread.
 */
public final class ClientCubeSource implements ICubeSource, IWorldEventListener {

    public static final ClientCubeSource INSTANCE = new ClientCubeSource();

    private static final int ALL = ICubeSourceListener.BLOCKS | ICubeSourceListener.LIGHT;
    /** An arrival replaces everything a cube holds. */
    private static final int ARRIVED = ALL | ICubeSourceListener.BIOMES;
    /** A render update over more cubes than this is taken to mean every loaded cube. */
    private static final int WHOLE_WORLD_CUBES = 32768;

    private final CubeEventQueue queue = new CubeEventQueue();
    private final CubeEventQueue.Loaded isLoaded = this::isLoaded;
    @Nullable private ICubeSourceListener listener;
    @Nullable private World world;
    @Nullable private CubeProviderClient cubes;
    private long identity;
    private long versions;
    private boolean announced;
    /** For each chunk column touched, the version at which its loaded cubes last changed. */
    private final Long2LongOpenHashMap surfaceVersions = new Long2LongOpenHashMap();
    /** The version at which every column last changed at once. */
    private long surfaceFloor;
    private final SurfaceHeights surfaces = new SurfaceHeights();
    /** The box the server last stated, the world it was stated for, and whether the listener has heard. */
    @Nullable private int[] held;
    @Nullable private World heldWorld;
    private boolean heldChanged;
    private final SettledState settled = new SettledState();
    /** A box the server said was sent before the source followed its world. */
    @Nullable private int[] pendingSent;
    @Nullable private World pendingSentWorld;
    /** The preview pool and its governor, made when a renderer first asks for the pool. */
    @Nullable private ClientPreviewWork previewWork;
    @Nullable private ClientBuildWork buildWork;
    /** One governor and one set of permits for both pools, made when a renderer first asks for either. */
    @Nullable private ClientPreviewGovernor workGovernor;
    @Nullable private io.github.opencubicchunks.cubicchunks.core.server.delivery.LivePermits workBudget;
    private int previewThreads;
    private int buildThreads;
    /** What the build pool reads of the world it follows: made anew for each world, so a change is seen. */
    @Nullable private ClientBuildWork.Cubes buildCubes;
    private final long[] frameNanos = new long[ClientPreviewGovernor.FRAMES];
    private int frames;
    private long lastFrame;
    private long nextGoverning = Long.MIN_VALUE;
    private final LoadedSurface loadedSurface = new LoadedSurface();

    private ClientCubeSource() {
    }

    // The routes by which cubes change.

    /** A cube's data arrived from the server and has been decoded into it. */
    public void cubeLoaded(Cube cube) {
        if (!follow(cube.getWorld())) {
            return;
        }
        settled.busy();
        stamp(cube, ARRIVED, ++versions);
        surfaceVersions.put(ChunkPos.asLong(cube.getX(), cube.getZ()), versions);
        queue.loaded(cube.getX(), cube.getY(), cube.getZ(), isLoaded);
    }

    /** A cube was removed from the client's cache. */
    public void cubeUnloaded(Cube cube) {
        if (cube.getWorld() != world) {
            return;
        }
        cube.sourceCopy = null;
        settled.busy();
        surfaceVersions.put(ChunkPos.asLong(cube.getX(), cube.getZ()), ++versions);
        queue.unloaded(cube.getX(), cube.getY(), cube.getZ(), isLoaded);
    }

    /** The server stated the box of cubes it keeps sent to this client (PacketHeldBox). */
    public void heldBox(World stated, int centerX, int centerY, int centerZ, int horizontal, int vertical) {
        int[] box = {centerX, centerY, centerZ, horizontal, vertical};
        if (stated == heldWorld && java.util.Arrays.equals(box, held)) {
            return;
        }
        held = box;
        heldWorld = stated;
        heldChanged = true;
        if (stated == world) {
            settled.box(box);
        }
    }

    /** The server said it has sent every cube of a box (PacketHeldBoxSent). */
    public void heldBoxSent(World stated, int centerX, int centerY, int centerZ, int horizontal, int vertical) {
        int[] box = {centerX, centerY, centerZ, horizontal, vertical};
        if (stated == world) {
            settled.boxSent(box);
        } else {
            pendingSent = box;
            pendingSentWorld = stated;
        }
    }

    /**
     * The server replaced a cube's light (PacketCubeLight): the cube's light changed and nothing else,
     * so only the vanilla renderer is told to rebuild, not every world listener.
     */
    public void lightReplaced(Cube cube) {
        if (cube.getWorld() != world) {
            return;
        }
        changed(cube, ICubeSourceListener.LIGHT, 0, 0, 0, 15, 15, 15);
        int x = cube.getX() << 4;
        int y = cube.getY() << 4;
        int z = cube.getZ() << 4;
        // One block wider: neighbors' faces against this cube take light from it.
        Minecraft.getMinecraft().renderGlobal.markBlockRangeForRenderUpdate(x - 1, y - 1, z - 1, x + 16, y + 16, z + 16);
    }

    /**
     * The server sent the light of some blocks of a cube (PacketCubeLightChanges); the bounds are local,
     * 0 to 15, inclusive. Only the vanilla renderer is told to rebuild, not every world listener.
     */
    public void lightChanged(Cube cube, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (cube.getWorld() != world) {
            return;
        }
        changed(cube, ICubeSourceListener.LIGHT, minX, minY, minZ, maxX, maxY, maxZ);
        int x = cube.getX() << 4;
        int y = cube.getY() << 4;
        int z = cube.getZ() << 4;
        Minecraft.getMinecraft().renderGlobal.markBlockRangeForRenderUpdate(x + minX - 1, y + minY - 1, z + minZ - 1,
                x + maxX + 1, y + maxY + 1, z + maxZ + 1);
    }

    /** The server corrected a column's height index, which the surface search starts from. */
    public void heightsChanged(World changed, int columnX, int columnZ) {
        if (changed == world) {
            surfaceVersions.put(ChunkPos.asLong(columnX, columnZ), ++versions);
        }
    }

    @Override
    public void notifyBlockUpdate(World worldIn, BlockPos pos, IBlockState oldState, IBlockState newState,
            int flags) {
        point(pos, ALL);
    }

    @Override
    public void notifyLightSet(BlockPos pos) {
        point(pos, ICubeSourceListener.LIGHT);
    }

    private void point(BlockPos pos, int kinds) {
        Cube cube = cubes.getLoadedCube(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (cube != null) {
            int x = pos.getX() & 15;
            int y = pos.getY() & 15;
            int z = pos.getZ() & 15;
            changed(cube, kinds, x, y, z, x, y, z);
        }
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = x1 >> 4, minY = y1 >> 4, minZ = z1 >> 4;
        int maxX = x2 >> 4, maxY = y2 >> 4, maxZ = z2 >> 4;
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > WHOLE_WORLD_CUBES) {
            for (Cube cube : cubes.loadedCubes()) {
                changed(cube, ALL, 0, 0, 0, 15, 15, 15);
            }
            surfaceVersions.clear();
            surfaceFloor = versions;
            return;
        }
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    Cube cube = cubes.getLoadedCube(cx, cy, cz);
                    if (cube != null) {
                        changed(cube, ALL, Math.max(x1 - (cx << 4), 0), Math.max(y1 - (cy << 4), 0),
                                Math.max(z1 - (cz << 4), 0), Math.min(x2 - (cx << 4), 15),
                                Math.min(y2 - (cy << 4), 15), Math.min(z2 - (cz << 4), 15));
                    }
                }
            }
        }
    }

    private void changed(Cube cube, int kinds, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        settled.busy();
        stamp(cube, kinds, ++versions);
        if ((kinds & ICubeSourceListener.BLOCKS) != 0) {
            surfaceVersions.put(ChunkPos.asLong(cube.getX(), cube.getZ()), versions);
        }
        queue.changed(cube.getX(), cube.getY(), cube.getZ(), kinds, minX, minY, minZ, maxX, maxY, maxZ, isLoaded);
    }

    /**
     * Moves a cube's versions to the given one: its version always, its block version for a change
     * of blocks or biomes, its light version for a change of light.
     */
    static void stamp(Cube cube, int kinds, long version) {
        cube.sourceVersion = version;
        if ((kinds & (ICubeSourceListener.BLOCKS | ICubeSourceListener.BIOMES)) != 0) {
            cube.sourceBlockVersion = version;
        }
        if ((kinds & ICubeSourceListener.LIGHT) != 0) {
            cube.sourceLightVersion = version;
        }
    }

    // The world the source follows.

    /** Makes the given world the one the source follows, if it is a cubic client world. */
    private boolean follow(World candidate) {
        if (candidate == world) {
            return true;
        }
        detach();
        if (!candidate.isRemote || !((ICubicWorld) candidate).isCubicWorld()
                || !(candidate.getChunkProvider() instanceof CubeProviderClient)) {
            return false;
        }
        world = candidate;
        cubes = (CubeProviderClient) candidate.getChunkProvider();
        settled.clear();
        buildCubes = new BuildCubes();
        if (heldWorld == candidate && held != null) {
            settled.box(held);
        }
        if (pendingSentWorld == candidate) {
            settled.boxSent(pendingSent);
        }
        pendingSent = null;
        pendingSentWorld = null;
        identity++;
        announced = false;
        candidate.addEventListener(this);
        return true;
    }

    private void detach() {
        if (world == null) {
            return;
        }
        io.github.opencubicchunks.cubicchunks.core.lighting.LightChecksum.dump("client", world.provider.getDimension(),
                cubes.loadedCubes());
        world.removeEventListener(this);
        queue.clear();
        surfaceVersions.clear();
        surfaceFloor = versions;
        surfaces.clear();
        loadedSurface.forget();
        if (announced && listener != null) {
            listener.worldDetached(identity);
        }
        if (heldWorld == world) {
            held = null;
            heldWorld = null;
        }
        settled.clear();
        world = null;
        cubes = null;
        buildCubes = null;
        announced = false;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Each pool's threads are counted a moment after its stop, with a world or without one.
        PoolStopCheck.INSTANCE.poll(System.nanoTime());
        World current = Minecraft.getMinecraft().world;
        ClientTerrainPreviews previews = ClientTerrainPreviews.INSTANCE;
        previews.tick(current);
        if (current != world) {
            if (current == null) {
                detach();
            } else {
                follow(current);
            }
        }
        if (world != null && io.github.opencubicchunks.cubicchunks.core.lighting.LightChecksum.DUMP != null
                && world.getTotalWorldTime() % 200 == 0) {
            io.github.opencubicchunks.cubicchunks.core.lighting.LightChecksum.dump("client",
                    world.provider.getDimension(), cubes.loadedCubes());
        }
        if (world != null) {
            settled.tick(((io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal) world)
                    .getLightingManager().hasQueuedLightWork());
        }
        if (previewWork != null) {
            // The pool's tasks belong to the attached preview: a change withdraws them and stops its threads.
            previewWork.bind(world == null ? null : terrainPreview());
        }
        if (buildWork != null) {
            // Its tasks belong to the world: a change drops them, releases their snapshots and stops its threads.
            buildWork.bind(world == null ? null : buildCubes);
            buildWork.pump();
        }
        if (workGovernor != null) {
            if (world != null) {
                governWork(System.nanoTime());
            } else {
                restGovernor();
            }
        }
        if (world == null || listener == null) {
            queue.clear();
            previews.takeChanged();
            return;
        }
        if (!announced) {
            // What is loaded already is read with forEachLoaded, not replayed as events; so is a
            // terrain preview that is already there.
            announced = true;
            queue.clear();
            previews.takeChanged();
            heldChanged = false;
            settled.takeSentChanged();
            listener.worldAttached(identity);
            return;
        }
        if (!queue.isEmpty()) {
            queue.drain(listener);
        }
        if (heldChanged) {
            heldChanged = false;
            if (heldWorld == world) {
                listener.heldBoxChanged();
            }
        }
        if (settled.takeSentChanged() && settled.boxSent()) {
            listener.heldBoxSent();
        }
        if (previews.takeChanged()) {
            listener.terrainPreviewChanged();
        }
    }

    /** The time between frames, for the preview pool's governor. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || workGovernor == null) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0) {
            frameNanos[frames++ % frameNanos.length] = now - lastFrame;
        }
        lastFrame = now;
        if (buildWork != null) {
            // Once a frame as well as once a tick, so threads that finish between ticks get work.
            buildWork.bind(world == null ? null : buildCubes);
            buildWork.pump();
        }
    }

    /**
     * With no world attached the governor does nothing: no cut, no raise, no permit-change line. What
     * the pools flagged and the frames drawn meanwhile are dropped, so the next world's first second
     * is judged on its own.
     */
    private void restGovernor() {
        if (previewWork != null) {
            previewWork.takeWaited();
            previewWork.takeRan();
            previewWork.takeFull();
        }
        if (buildWork != null) {
            buildWork.takeWaited();
            buildWork.takeRan();
            buildWork.takeFull();
        }
        frames = 0;
        lastFrame = 0;
        nextGoverning = Long.MIN_VALUE;
    }

    /** Once a second: the governor reads the latest frames and, in single player, the server's latest ticks. */
    private void governWork(long now) {
        if (now < nextGoverning) {
            return;
        }
        nextGoverning = now + 1_000_000_000L;
        int count = Math.min(frames, frameNanos.length);
        long[] latestFrames = new long[count];
        for (int i = 0; i < count; i++) {
            latestFrames[i] = frameNanos[Math.floorMod(frames - count + i, frameNanos.length)];
        }
        long[] latestTicks = new long[0];
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server != null) {
            int tick = server.getTickCounter();
            long[] ring = server.tickTimeArray;
            int ticks = Math.min(ClientPreviewGovernor.RAISE_TICKS, Math.min(tick, ring.length));
            latestTicks = new long[ticks];
            for (int i = 0; i < ticks; i++) {
                latestTicks[i] = ring[Math.floorMod(tick - ticks + 1 + i, ring.length)];
            }
        }
        // Both pools' flags are read every time, so neither carries a stale one into the next second.
        boolean previewWaited = previewWork != null && previewWork.takeWaited();
        boolean buildWaited = buildWork != null && buildWork.takeWaited();
        boolean previewRan = previewWork != null && previewWork.takeRan();
        boolean buildRan = buildWork != null && buildWork.takeRan();
        boolean previewFull = previewWork != null && previewWork.takeFull();
        boolean buildFull = buildWork != null && buildWork.takeFull();
        int queued = (previewWork == null ? 0 : previewWork.queuedNow()) + (buildWork == null ? 0 : buildWork.queuedNow());
        // Demand is a job that waited for a permit, work not yet started, or a pool that ran full: a renderer
        // that hands in no more than running() allows never makes a job wait, yet keeps the pool full.
        boolean waited = previewWaited || buildWaited;
        boolean demand = waited || queued > 0 || previewFull || buildFull;
        // The preview's own build pool counts as work that ran: it shares the machine, not the permits.
        boolean ran = previewRan || buildRan || ClientTerrainPreviews.INSTANCE.buildActive() > 0;
        int before = workBudget.permits();
        java.util.List<String> wanted = new java.util.ArrayList<>(3);
        if (queued > 0) {
            wanted.add(queued + " queued");
        }
        if (previewFull || buildFull) {
            wanted.add("a pool ran full");
        }
        if (waited) {
            wanted.add("a job waited");
        }
        int after = workGovernor.update(now, latestFrames, latestTicks, demand, ran, String.join(", ", wanted));
        workBudget.set(after);
        if (after != before) {
            CubicChunks.LOGGER.info("Client work permits {} -> {}: {} (frame p95 {} ms, server tick p95 {}, waited {}, "
                            + "ran {}, queued {}, full {})", before, after, workGovernor.lastReason(),
                    String.format(java.util.Locale.ROOT, "%.1f", workGovernor.lastFrameMs()),
                    server == null ? "remote server"
                            : String.format(java.util.Locale.ROOT, "%.1f ms", workGovernor.lastTickMs()),
                    waited, ran, queued, previewFull || buildFull);
        }
    }

    // ICubeSource.

    @Override
    public void setListener(@Nullable ICubeSourceListener newListener) {
        if (listener != null && announced) {
            listener.worldDetached(identity);
        }
        listener = newListener;
        announced = false;
        queue.clear();
    }

    @Override
    public boolean attached() {
        return world != null;
    }

    @Override
    public long worldIdentity() {
        return identity;
    }

    @Override
    public int minCubeY() {
        return Coords.blockToCube(((ICubicWorld) world).getMinHeight());
    }

    @Override
    public int maxCubeY() {
        return Coords.blockToCube(((ICubicWorld) world).getMaxHeight());
    }

    @Override
    public boolean hasSkyLight() {
        return world.provider.hasSkyLight();
    }

    @Override
    public boolean isLoaded(int cubeX, int cubeY, int cubeZ) {
        return cubes != null && cubes.getLoadedCube(cubeX, cubeY, cubeZ) != null;
    }

    @Override
    public long version(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes == null ? null : cubes.getLoadedCube(cubeX, cubeY, cubeZ);
        return cube == null ? -1 : cube.sourceVersion;
    }

    @Override
    public long blockVersion(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes == null ? null : cubes.getLoadedCube(cubeX, cubeY, cubeZ);
        return cube == null ? -1 : cube.sourceBlockVersion;
    }

    @Override
    public long lightVersion(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes == null ? null : cubes.getLoadedCube(cubeX, cubeY, cubeZ);
        return cube == null ? -1 : cube.sourceLightVersion;
    }

    @Override
    public long lightChecksum(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes == null ? null : cubes.getLoadedCube(cubeX, cubeY, cubeZ);
        return cube == null ? -1 : io.github.opencubicchunks.cubicchunks.core.lighting.LightChecksum.of(cube);
    }

    @Override
    public int quietTicks() {
        return world == null ? 0 : settled.quietTicks();
    }

    @Override
    public boolean heldBoxSent() {
        return world != null && settled.boxSent();
    }

    @Nullable
    @Override
    public int[] heldBox() {
        return world != null && heldWorld == world && held != null ? held.clone() : null;
    }

    @Override
    public int loadedNeighbors(int cubeX, int cubeY, int cubeZ) {
        return cubes == null ? 0 : ICubeSource.super.loadedNeighbors(cubeX, cubeY, cubeZ);
    }

    @Override
    public int light(int blockX, int blockY, int blockZ) {
        Cube cube = cubes == null ? null : cubes.getLoadedCube(blockX >> 4, blockY >> 4, blockZ >> 4);
        if (cube == null) {
            return -1;
        }
        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
        int sky = world.provider.hasSkyLight() ? cube.getCachedLightFor(net.minecraft.world.EnumSkyBlock.SKY, pos) : 0;
        return sky << 4 | cube.getCachedLightFor(net.minecraft.world.EnumSkyBlock.BLOCK, pos);
    }

    @Override
    public ICubeSnapshot snapshotCenter(int cubeX, int cubeY, int cubeZ) {
        if (world == null) {
            throw new IllegalStateException("No cubic client world is attached");
        }
        boolean sky = world.provider.hasSkyLight();
        CubeCopy[] copies = new CubeCopy[27];
        java.util.Arrays.fill(copies, CubeCopy.NOTHING);
        copies[13] = copy(cubeX, cubeY, cubeZ, sky);
        return new CubeSnapshot(cubeX, cubeY, cubeZ, identity, sky, world.getWorldType(), copies);
    }

    @Override
    public void forEachLoaded(CubeVisitor visitor) {
        if (cubes != null) {
            for (Cube cube : cubes.loadedCubes()) {
                visitor.visit(cube.getX(), cube.getY(), cube.getZ());
            }
        }
    }

    @Override
    public ICubeSnapshot snapshot(int cubeX, int cubeY, int cubeZ) {
        if (world == null) {
            throw new IllegalStateException("No cubic client world is attached");
        }
        boolean sky = world.provider.hasSkyLight();
        CubeCopy[] copies = new CubeCopy[27];
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    copies[index++] = copy(cubeX + dx, cubeY + dy, cubeZ + dz, sky);
                }
            }
        }
        return new CubeSnapshot(cubeX, cubeY, cubeZ, identity, sky, world.getWorldType(), copies);
    }

    @Nullable
    @Override
    public ICubicTerrainPreview terrainPreview() {
        return ClientTerrainPreviews.INSTANCE.preview(world);
    }

    @Override
    public int surfaceHeights(int columnX, int columnZ, int[] ground, int[] liquid, boolean[] fromLoaded,
            boolean allowPreview) {
        if (world == null) {
            return ICubeSource.super.surfaceHeights(columnX, columnZ, ground, liquid, fromLoaded, allowPreview);
        }
        Chunk column = cubes.getLoadedColumn(columnX, columnZ);
        try {
            return surfaces.fill(column == null ? null : loadedSurface.of(column, columnX, columnZ),
                    terrainPreview(), columnX, columnZ, ground, liquid, fromLoaded, allowPreview);
        } finally {
            loadedSurface.forget();
        }
    }

    @Override
    public int cubeContent(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes == null ? null : cubes.getLoadedCube(cubeX, cubeY, cubeZ);
        return SurfaceHeights.content(cube != null, cube == null ? null : cube.getStorage());
    }

    @Override
    public long surfaceVersion(int columnX, int columnZ) {
        if (world == null) {
            return -1;
        }
        return Math.max(surfaceFloor, surfaceVersions.get(ChunkPos.asLong(columnX, columnZ)));
    }

    /** A chunk column's loaded cubes for the surface search, keeping the cubes it has looked up. */
    private final class LoadedSurface implements SurfaceHeights.LoadedColumn {
        private static final int WINDOW = 64;
        private final Cube[] held = new Cube[WINDOW];
        private final boolean[] asked = new boolean[WINDOW];
        private IHeightMap tops;
        private int columnX;
        private int columnZ;
        private int baseCubeY;
        private boolean based;
        private int highest;

        LoadedSurface of(Chunk column, int x, int z) {
            forget();
            tops = ((IColumn) column).getOpacityIndex();
            columnX = x;
            columnZ = z;
            highest = Integer.MIN_VALUE;
            for (ICube cube : ((IColumn) column).getLoadedCubes()) {
                if (!cube.isEmpty()) {
                    highest = Math.max(highest, (cube.getY() << 4) + 15);
                }
            }
            return this;
        }

        @Override
        public int highestBlocks() {
            return highest;
        }

        @Override
        public boolean holdsBlocks(int cubeY) {
            Cube cube = cube(cubeY);
            return cube != null && !cube.isEmpty();
        }

        void forget() {
            java.util.Arrays.fill(held, null);
            java.util.Arrays.fill(asked, false);
            tops = null;
            based = false;
        }

        @Override
        public int top(int localX, int localZ) {
            return tops.getTopBlockY(localX, localZ);
        }

        @Nullable
        @Override
        public IBlockState state(int localX, int blockY, int localZ) {
            Cube cube = cube(blockY >> 4);
            return cube == null ? null : cube.getBlockState(localX, blockY, localZ);
        }

        @Nullable
        private Cube cube(int cubeY) {
            if (!based) {
                // The first block asked for is a column's top; the search only goes down from tops.
                based = true;
                baseCubeY = cubeY + 8;
            }
            int slot = baseCubeY - cubeY;
            Cube cube;
            if (slot < 0 || slot >= WINDOW) {
                cube = cubes.getLoadedCube(columnX, cubeY, columnZ);
            } else if (asked[slot]) {
                cube = held[slot];
            } else {
                asked[slot] = true;
                cube = held[slot] = cubes.getLoadedCube(columnX, cubeY, columnZ);
            }
            return cube;
        }

        @Override
        public int floor() {
            return ((ICubicWorld) world).getMinHeight();
        }
    }

    /** The governor and permits both pools share. */
    private void ensureWorkBudget() {
        if (workGovernor != null) {
            return;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        previewThreads = ClientPreviewWork.size(CubicChunksConfig.clientPreviewThreads, processors);
        buildThreads = ClientBuildWork.size(CubicChunksConfig.clientBuildThreads, processors);
        workGovernor = new ClientPreviewGovernor(Math.max(previewThreads, buildThreads),
                CubicChunksConfig.clientPreviewFrameBudgetMs);
        workBudget = new io.github.opencubicchunks.cubicchunks.core.server.delivery.LivePermits(workGovernor.permits());
    }

    @Override
    public IClientPreviewWork previewWork() {
        if (previewWork == null) {
            ensureWorkBudget();
            previewWork = new ClientPreviewWork(previewThreads, workBudget);
            previewWork.bind(world == null ? null : terrainPreview());
        }
        return previewWork;
    }

    @Override
    public io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork buildWork() {
        if (buildWork == null) {
            ensureWorkBudget();
            buildWork = new ClientBuildWork(buildThreads, workBudget);
            buildWork.bind(world == null ? null : buildCubes);
        }
        return buildWork;
    }

    /** The build pool's reads of one world. */
    private final class BuildCubes implements ClientBuildWork.Cubes {
        @Override public boolean isLoaded(int x, int y, int z) {
            return ClientCubeSource.this.isLoaded(x, y, z);
        }

        @Override public ICubeSnapshot snapshot(int x, int y, int z, boolean centerOnly) {
            return centerOnly ? snapshotCenter(x, y, z) : ClientCubeSource.this.snapshot(x, y, z);
        }

        @Override public long version(int x, int y, int z) {
            return ClientCubeSource.this.version(x, y, z);
        }

        @Override public long blockVersion(int x, int y, int z) {
            return ClientCubeSource.this.blockVersion(x, y, z);
        }

        @Override public long lightVersion(int x, int y, int z) {
            return ClientCubeSource.this.lightVersion(x, y, z);
        }
    }

    private CubeCopy copy(int x, int y, int z, boolean sky) {
        Cube cube = cubes.getLoadedCube(x, y, z);
        if (cube != null) {
            return CubeCopy.of(cube, sky);
        }
        Chunk column = cubes.getLoadedColumn(x, z);
        return column == null ? CubeCopy.NOTHING : CubeCopy.absent(column, y, sky);
    }

    /** For the diagnostic: the loaded cube at a position. */
    @Nullable
    Cube loaded(int x, int y, int z) {
        return cubes == null ? null : cubes.getLoadedCube(x, y, z);
    }

    /** For the diagnostic: the client's cube cache, or null with no world attached. */
    @Nullable
    CubeProviderClient cubes() {
        return cubes;
    }

    // The rest of the game's world listener, which the source has no use for.

    @Override
    public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent soundIn, SoundCategory category,
            double x, double y, double z, float volume, float pitch) {
    }

    @Override
    public void playRecord(SoundEvent soundIn, BlockPos pos) {
    }

    @Override
    public void spawnParticle(int particleID, boolean ignoreRange, double xCoord, double yCoord, double zCoord,
            double xSpeed, double ySpeed, double zSpeed, int... parameters) {
    }

    @Override
    public void spawnParticle(int id, boolean ignoreRange, boolean minimiseParticleLevel, double x, double y,
            double z, double xSpeed, double ySpeed, double zSpeed, int... parameters) {
    }

    @Override
    public void onEntityAdded(Entity entityIn) {
    }

    @Override
    public void onEntityRemoved(Entity entityIn) {
    }

    @Override
    public void broadcastSound(int soundID, BlockPos pos, int data) {
    }

    @Override
    public void playEvent(EntityPlayer player, int type, BlockPos blockPosIn, int data) {
    }

    @Override
    public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
    }
}
