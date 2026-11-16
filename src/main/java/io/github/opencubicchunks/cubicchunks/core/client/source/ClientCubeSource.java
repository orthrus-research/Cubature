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

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;
import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.IHeightMap;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.core.client.CubeProviderClient;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
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
    private final LoadedSurface loadedSurface = new LoadedSurface();

    private ClientCubeSource() {
    }

    // The routes by which cubes change.

    /** A cube's data arrived from the server and has been decoded into it. */
    public void cubeLoaded(Cube cube) {
        if (!follow(cube.getWorld())) {
            return;
        }
        cube.sourceVersion = ++versions;
        surfaceVersions.put(ChunkPos.asLong(cube.getX(), cube.getZ()), versions);
        queue.loaded(cube.getX(), cube.getY(), cube.getZ(), isLoaded);
    }

    /** A cube was removed from the client's cache. */
    public void cubeUnloaded(Cube cube) {
        if (cube.getWorld() != world) {
            return;
        }
        cube.sourceCopy = null;
        surfaceVersions.put(ChunkPos.asLong(cube.getX(), cube.getZ()), ++versions);
        queue.unloaded(cube.getX(), cube.getY(), cube.getZ(), isLoaded);
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
        cube.sourceVersion = ++versions;
        if ((kinds & ICubeSourceListener.BLOCKS) != 0) {
            surfaceVersions.put(ChunkPos.asLong(cube.getX(), cube.getZ()), versions);
        }
        queue.changed(cube.getX(), cube.getY(), cube.getZ(), kinds, minX, minY, minZ, maxX, maxY, maxZ, isLoaded);
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
        identity++;
        announced = false;
        candidate.addEventListener(this);
        return true;
    }

    private void detach() {
        if (world == null) {
            return;
        }
        world.removeEventListener(this);
        queue.clear();
        surfaceVersions.clear();
        surfaceFloor = versions;
        surfaces.clear();
        loadedSurface.forget();
        if (announced && listener != null) {
            listener.worldDetached(identity);
        }
        world = null;
        cubes = null;
        announced = false;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
            listener.worldAttached(identity);
            return;
        }
        if (!queue.isEmpty()) {
            queue.drain(listener);
        }
        if (previews.takeChanged()) {
            listener.terrainPreviewChanged();
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
