package io.github.opencubicchunks.cubicchunks.core.lighting;

import static org.junit.Assert.*;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.IHeightMap;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.lighting.phosphor.PhosphorLightEngine;
import io.github.opencubicchunks.cubicchunks.core.lighting.phosphor.PooledLightUpdateQueue;
import io.github.opencubicchunks.cubicchunks.core.world.IColumnInternal;
import io.github.opencubicchunks.cubicchunks.core.world.ICubeProviderInternal;
import io.github.opencubicchunks.cubicchunks.core.world.ServerHeightMap;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.WorldInfo;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Executes existing initial-light and surface paths, without starting a server or weaving Minecraft classes. */
public class TestEmptyCubeInitialLight {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void aboveHeightAirOnlyQueuesRedundantSkyChecks() throws Exception {
        Fixture fixture = new Fixture(true);
        byte[] blocksAndLight = fixture.blocksAndLight();
        byte[] flags = fixture.flags(false);
        assertTrue(fixture.whollyAbove());
        assertNull(fixture.target.getStorage());
        assertEquals("The actual all-air primer constructor marks the cube modified", true,
                field(fixture.target, Cube.class, "isModified"));
        assertTrue(fixture.target.needsSaving());
        fullFirstLight(fixture);
        assertEquals(4096, fixture.queued(EnumSkyBlock.SKY));
        assertEquals(0, fixture.queued(EnumSkyBlock.BLOCK));
        assertArrayEquals(blocksAndLight, fixture.blocksAndLight());
        fixture.manager.processUpdates();
        assertEquals(0, fixture.queued(EnumSkyBlock.SKY));
        assertEquals(0, fixture.queued(EnumSkyBlock.BLOCK));
        assertFalse(fixture.engine.hasLightUpdates());
        fixture.assertQueuesEmpty();
        assertArrayEquals(blocksAndLight, fixture.blocksAndLight());
        assertArrayEquals(flags, fixture.flags(false));
        assertEquals(Collections.singletonList(fixture.target.getCoords()), fixture.provider.fetches);
        assertTrue(fixture.world.lightNotifications.isEmpty());
        assertTrue(fixture.listener.updates.isEmpty());
        assertNull(fixture.target.getStorage());
    }

    @Test public void trackingStillDirtiesTheColumnAndPublishesTheWholeFootprint() throws Exception {
        surfaceBookkeeping(false);
    }

    @Test public void trackingStillProcessesSavedHeightRelights() throws Exception {
        surfaceBookkeeping(true);
    }

    private static void surfaceBookkeeping(boolean staleHeight) throws Exception {
        Fixture fixture = new Fixture(true);
        byte[] heightBytes = fixture.column.heights.getData();
        byte[] blocksAndLight = fixture.blocksAndLight();
        byte[] otherFlags = fixture.flags(true);
        int lowest = fixture.column.heights.getLowestTopBlockY();
        LightingManager.CubeLightData data = (LightingManager.CubeLightData) fixture.target.getCubeLightData();
        data.lastHeightMap = fixture.column.getHeightMap().clone();
        if (staleHeight) for (int i = 0; i < data.lastHeightMap.length; i++) data.lastHeightMap[i]++;
        fullFirstLight(fixture);
        fixture.target.setInitialLightingDone(true);
        fullTrackSurface(fixture.target);
        assertTrue(fixture.target.isInitialLightingDone());
        assertTrue(fixture.target.isSurfaceTracked());
        assertFalse(fixture.target.isPopulated());
        assertFalse(fixture.target.isFullyPopulated());
        assertEquals(true, field(fixture.target, Cube.class, "isModified"));
        assertTrue(fixture.target.needsSaving());
        assertTrue(fixture.column.modified);
        assertEquals(4096, fixture.column.modifiedCalls);
        assertEquals(1, fixture.column.stagingRemovals);
        assertFalse(fixture.column.staging.contains(fixture.target));
        assertNull(data.lastHeightMap);
        assertArrayEquals(heightBytes, fixture.column.heights.getData());
        assertEquals(lowest, fixture.column.heights.getLowestTopBlockY());
        List<Long> expected = new ArrayList<>();
        for (int x = 0; x <= 16; x++) for (int z = 0; z <= 16; z++) expected.add(key(x, z));
        assertEquals(expected, fixture.listener.updates);
        assertEquals(staleHeight ? 4352 : 4096, fixture.queued(EnumSkyBlock.SKY));
        fixture.manager.processUpdates();
        assertFalse(fixture.engine.hasLightUpdates());
        fixture.assertQueuesEmpty();
        assertArrayEquals(blocksAndLight, fixture.blocksAndLight());
        assertArrayEquals(otherFlags, fixture.flags(true));
        assertTrue(fixture.world.lightNotifications.isEmpty());
        assertNull(fixture.target.getStorage());
    }

    @Test public void shortcutMatchesTheActualFullPipelineAfterBothQueuesDrain() throws Exception {
        // Open sky, changed saved height, two stale in-cube tops, and a world without skylight.
        for (int mode = 0; mode < 5; mode++) {
            Fixture full = new Fixture(mode != 4), fast = new Fixture(mode != 4);
            for (Fixture fixture : Arrays.asList(full, fast)) {
                if (mode == 2 || mode == 3) fixture.column.heights.onOpacityChange(15, mode == 2 ? 64 : 79, 15, 255);
                if (mode == 1) {
                    LightingManager.CubeLightData data = (LightingManager.CubeLightData) fixture.target.getCubeLightData();
                    data.lastHeightMap = fixture.column.getHeightMap().clone();
                    for (int i = 0; i < data.lastHeightMap.length; i++) data.lastHeightMap[i]++;
                }
            }
            boolean eligible = fast.whollyAbove();
            fullFirstLight(full);
            full.target.setInitialLightingDone(true);
            fullTrackSurface(full.target);
            fast.manager.doFirstLight(fast.target);
            assertEquals(eligible ? 0 : full.queued(EnumSkyBlock.SKY), fast.queued(EnumSkyBlock.SKY));
            fast.target.setInitialLightingDone(true);
            fast.target.trackSurface();
            for (Fixture fixture : Arrays.asList(full, fast)) { fixture.manager.processUpdates(); fixture.assertQueuesEmpty(); }
            assertArrayEquals(full.blocksAndLight(), fast.blocksAndLight());
            assertArrayEquals(full.flags(false), fast.flags(false));
            assertEquals(full.column.heights.getLowestTopBlockY(), fast.column.heights.getLowestTopBlockY());
            assertEquals(full.listener.updates, fast.listener.updates);
            assertEquals(full.world.lightNotifications, fast.world.lightNotifications);
            assertEquals(4096, full.column.modifiedCalls);
            assertEquals(eligible ? 1 : 4096, fast.column.modifiedCalls);
        }
    }

    @Test public void oneFormerlyOpaqueTopInsideTheCubeChangesTheHeightMap() throws Exception {
        for (int top : new int[] {64, 79}) {
            Fixture fixture = new Fixture(true);
            fixture.column.heights.onOpacityChange(15, top, 15, 255);
            assertFalse("255 low columns are insufficient", fixture.whollyAbove());
            byte[] before = fixture.column.heights.getData();
            fixture.manager.doFirstLight(fixture.target);
            fixture.target.setInitialLightingDone(true);
            fixture.target.trackSurface();
            assertFalse(Arrays.equals(before, fixture.column.heights.getData()));
            assertEquals(63, fixture.column.getTopYWithStaging(15, 15));
            fixture.manager.processUpdates();
            assertFalse(fixture.engine.hasLightUpdates());
            fixture.assertQueuesEmpty();
        }
    }

    @Test public void fixtureSeparatesTheStrictBoundaryBuriedAndStoredCases() throws Exception {
        Fixture boundary = new Fixture(true);
        boundary.column.heights.onOpacityChange(15, 64, 15, 255);
        assertEquals(boundary.target.getCoords().getMinBlockY(), boundary.column.getTopYWithStaging(15, 15));
        assertFalse(boundary.whollyAbove());
        Fixture buried = new Fixture(true);
        buried.column.heights.onOpacityChange(15, 80, 15, 255);
        assertFalse(buried.whollyAbove());
        Fixture stored = new Fixture(true);
        stored.target.setStorage(new ExtendedBlockStorage(64, true));
        assertFalse(stored.whollyAbove());
        stored.target.trackSurface();
        assertEquals(4096, stored.column.modifiedCalls);
        assertFalse(Cube.isStorageLessAboveSurface(new Cube(stored.column, 4) { }));
        Fixture noSky = new Fixture(false);
        assertFalse(noSky.whollyAbove());
        noSky.manager.doFirstLight(noSky.target);
        assertEquals(0, noSky.queued(EnumSkyBlock.SKY));
        assertEquals(0, noSky.queued(EnumSkyBlock.BLOCK));
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private static void fullFirstLight(Fixture fixture) throws Exception {
        Method method = FirstLightProcessor.class.getDeclaredMethod("diffuseSkylightFromBlocks", ICube.class);
        method.setAccessible(true);
        method.invoke(field(fixture.manager, LightingManager.class, "firstLightProcessor"), fixture.target);
    }

    private static void fullTrackSurface(Cube cube) throws Exception {
        Method method = Cube.class.getDeclaredMethod("trackSurfaceFromBlocks");
        method.setAccessible(true);
        method.invoke(cube);
    }

    private static Object field(Object owner, Class<?> declaration, String name) throws Exception {
        Field field = declaration.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, Class<?> declaration, String name, Object value) throws Exception {
        Field field = declaration.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static <T> T withoutConstructor(Class<T> type) throws Exception {
        // Exact test-runtime allocator; there is no alternate API or server-start fallback.
        Class<?> allocatorType = Class.forName("sun.misc.Unsafe");
        Field singleton = allocatorType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(allocatorType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static final class Fixture {
        final FixtureWorld world = withoutConstructor(FixtureWorld.class);
        final FixtureListener listener = withoutConstructor(FixtureListener.class);
        final FixtureProvider provider = new FixtureProvider(world);
        final LightingManager manager;
        final PhosphorLightEngine engine;
        final FixtureColumn column;
        final Cube target;

        Fixture(boolean sky) throws Exception {
            WorldProviderSurface dimension = new WorldProviderSurface() {
                @Override public boolean hasSkyLight() { return sky; }
            };
            Profiler profiler = new Profiler();
            WorldInfo info = new WorldInfo(new NBTTagCompound());
            setField(world, World.class, "provider", dimension);
            setField(world, World.class, "profiler", profiler);
            setField(world, World.class, "worldInfo", info);
            world.providerFixture = provider;
            world.listenerFixture = listener;
            world.lightNotifications = new ArrayList<>();
            listener.updates = new ArrayList<>();
            assertSame(dimension, world.provider);
            assertSame(profiler, world.profiler);
            assertSame(info, world.getWorldInfo());
            assertFalse(world.isRemote);
            manager = new LightingManager(world);
            world.managerFixture = manager;
            engine = (PhosphorLightEngine) field(manager, LightingManager.class, "lightEngine");
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                FixtureColumn made = new FixtureColumn(world, x, z);
                provider.columns.put(key(x, z), made);
                for (int y = 3; y <= 5; y++) {
                    CubePrimer primer = new CubePrimer();
                    if (y == 3) for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++)
                        primer.setBlockState(bx, 15, bz, Blocks.STONE.getDefaultState());
                    Cube cube = new Cube(made, y, primer);
                    made.addCube(cube);
                    cube.setCubeLoaded();
                    cube.setSurfaceTracked(true);
                    cube.setInitialLightingDone(true);
                    cube.markSaved();
                }
                made.setModified(false);
                made.modifiedCalls = 0;
            }
            column = provider.columns.get(key(0, 0));
            target = new Cube(column, 4, new CubePrimer());
            column.addCube(target);
            target.setCubeLoaded();
            column.addToStagingHeightmap(target);
            // New real queues have size zero before their empty flags are first primed.
            manager.processUpdates();
            assertEquals(0, queued(EnumSkyBlock.SKY));
            assertEquals(0, queued(EnumSkyBlock.BLOCK));
            assertFalse(engine.hasLightUpdates());
            assertQueuesEmpty();
            provider.fetches.clear();
        }

        boolean whollyAbove() {
            return Cube.isStorageLessAboveSurface(target);
        }

        int queued(EnumSkyBlock type) throws Exception {
            return ((PooledLightUpdateQueue[]) field(engine, PhosphorLightEngine.class, "queuedLightUpdates"))[type.ordinal()].size();
        }

        void assertQueuesEmpty() throws Exception {
            List<PooledLightUpdateQueue> queues = new ArrayList<>();
            for (String name : new String[] {"queuedLightUpdates", "queuedDarkenings", "queuedBrightenings"})
                queues.addAll(Arrays.asList((PooledLightUpdateQueue[]) field(engine, PhosphorLightEngine.class, name)));
            for (String name : new String[] {"initialBrightenings", "initialDarkenings"})
                queues.add((PooledLightUpdateQueue) field(engine, PhosphorLightEngine.class, name));
            assertEquals(36, queues.size());
            for (PooledLightUpdateQueue queue : queues) {
                assertEquals(0, queue.size());
                assertTrue(queue.isEmpty());
            }
        }

        byte[] blocksAndLight() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                for (FixtureColumn current : provider.columns.values()) for (Cube cube : current.cubes.values()) {
                    out.writeBoolean(cube.getStorage() == null);
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        BlockPos pos = cube.getCoords().getMinBlockPos().add(x, y, z);
                        out.writeInt(Block.getStateId(cube.getBlockState(pos)));
                        out.writeByte(cube.getCachedLightFor(EnumSkyBlock.SKY, pos));
                        out.writeByte(cube.getCachedLightFor(EnumSkyBlock.BLOCK, pos));
                    }
                }
            }
            return bytes.toByteArray();
        }

        byte[] flags(boolean omitTarget) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                for (FixtureColumn current : provider.columns.values()) {
                    if (!omitTarget || current != column) {
                        out.writeBoolean(current.modified);
                        out.writeInt(current.stagingRemovals);
                        out.writeInt(current.staging.size());
                    }
                    out.write(current.heights.getData());
                    for (Cube cube : current.cubes.values()) {
                        if (omitTarget && cube == target) continue;
                        out.writeBoolean(cube.isCubeLoaded());
                        out.writeBoolean(cube.isPopulated());
                        out.writeBoolean(cube.isFullyPopulated());
                        out.writeBoolean(cube.isInitialLightingDone());
                        out.writeBoolean(cube.isSurfaceTracked());
                        out.writeBoolean((boolean) field(cube, Cube.class, "isModified"));
                        out.writeBoolean(cube.needsSaving());
                        LightingManager.CubeLightData data = (LightingManager.CubeLightData) cube.getCubeLightData();
                        out.writeLong(data.neighborLightChecksBlock);
                        out.writeLong(data.neighborLightChecksSky);
                        for (long value : data.lastSaveHeightMapInfo) out.writeLong(value);
                        assertNull(data.lastHeightMap);
                    }
                }
            }
            return bytes.toByteArray();
        }
    }

    private static final class FixtureColumn extends Chunk implements IColumnInternal {
        final ServerHeightMap heights;
        final Map<Integer, Cube> cubes = new TreeMap<>();
        final Set<ICube> staging = new HashSet<>();
        boolean modified;
        int modifiedCalls, stagingRemovals;
        FixtureColumn(World world, int x, int z) {
            super(world, x, z);
            heights = new ServerHeightMap(getHeightMap());
            for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++) heights.onOpacityChange(bx, 63, bz, 255);
        }
        @Override public int getX() { return x; }
        @Override public int getZ() { return z; }
        @Override public IHeightMap getOpacityIndex() { return heights; }
        @Override public int getTopYWithStaging(int x, int z) { return heights.getTopBlockY(x, z); }
        @Override public int getHeightValue(int x, int y, int z) { return heights.getTopBlockY(x, z) + 1; }
        @Override public boolean shouldTick() { return false; }
        @Override public Collection<? extends ICube> getLoadedCubes() { return cubes.values(); }
        @Override public Iterable<? extends ICube> getLoadedCubes(int first, int last) {
            List<Cube> result = new ArrayList<>();
            for (Map.Entry<Integer, Cube> entry : cubes.entrySet())
                if (entry.getKey() >= Math.min(first, last) && entry.getKey() <= Math.max(first, last)) result.add(entry.getValue());
            if (first > last) Collections.reverse(result);
            return result;
        }
        @Override public ICube getLoadedCube(int y) { return cubes.get(y); }
        @Override public ICube getCube(int y) { throw new AssertionError("Loading cube read"); }
        @Override public void addCube(ICube cube) { cubes.put(cube.getY(), (Cube) cube); }
        @Override public ICube removeCube(int y) { return cubes.remove(y); }
        @Override public boolean hasLoadedCubes() { return !cubes.isEmpty(); }
        @Override public void preCacheCube(ICube cube) { }
        @Override public ChunkPrimer getCompatGenerationPrimer() { throw new AssertionError("Compatibility generation"); }
        @Override public void addToStagingHeightmap(ICube cube) { staging.add(cube); }
        @Override public void removeFromStagingHeightmap(ICube cube) { stagingRemovals++; staging.remove(cube); }
        @Override public void setModified(boolean value) { super.setModified(value); modified = value; modifiedCalls++; }
        @Override public void setLightFor(EnumSkyBlock type, BlockPos pos, int value) {
            throw new AssertionError("The above-height proof must not write light");
        }
    }

    private static final class FixtureProvider extends ChunkProviderServer implements ICubeProviderInternal {
        final Map<Long, FixtureColumn> columns = new TreeMap<>();
        final List<CubePos> fetches = new ArrayList<>();
        FixtureProvider(WorldServer world) { super(world, null, null); }
        @Override public Cube getLoadedCube(int x, int y, int z) {
            fetches.add(new CubePos(x, y, z));
            FixtureColumn column = columns.get(key(x, z));
            return column == null ? null : column.cubes.get(y);
        }
        @Override public Cube getLoadedCube(CubePos pos) { return getLoadedCube(pos.getX(), pos.getY(), pos.getZ()); }
        @Override public Cube getCube(int x, int y, int z) { throw new AssertionError("Loading cube read"); }
        @Override public Cube getCube(CubePos pos) { throw new AssertionError("Loading cube read"); }
        @Override public Chunk getLoadedColumn(int x, int z) { return columns.get(key(x, z)); }
        @Override public Chunk provideColumn(int x, int z) {
            Chunk column = getLoadedColumn(x, z);
            if (column == null) throw new AssertionError("Loading column read");
            return column;
        }
        @Override public Chunk getLoadedChunk(int x, int z) { return getLoadedColumn(x, z); }
        @Override public Chunk provideChunk(int x, int z) { return provideColumn(x, z); }
    }

    private static final class FixtureListener extends PlayerChunkMap implements SurfaceHeightListener {
        List<Long> updates;
        private FixtureListener() { super(null); }
        @Override public void heightUpdated(int x, int z) { updates.add(key(x, z)); }
    }

    private static final class FixtureWorld extends WorldServer implements ICubicWorldInternal {
        FixtureProvider providerFixture;
        FixtureListener listenerFixture;
        LightingManager managerFixture;
        List<BlockPos> lightNotifications;
        private FixtureWorld() { super(null, null, null, 0, null); }
        @Override public FixtureProvider getChunkProvider() { return providerFixture; }
        @Override public PlayerChunkMap getPlayerChunkMap() { return listenerFixture; }
        @Override public ILightingManager getLightingManager() { return managerFixture; }
        @Override public FixtureProvider getCubeCache() { return providerFixture; }
        @Override public boolean isCubicWorld() { return true; }
        @Override public int getMinHeight() { return -256; }
        @Override public int getMaxHeight() { return 512; }
        @Override public int getMinGenerationHeight() { return -256; }
        @Override public int getMaxGenerationHeight() { return 512; }
        @Override public void tickCubicWorld() { throw new AssertionError("World tick"); }
        @Override public Cube getCubeFromCubeCoords(int x, int y, int z) { return providerFixture.getLoadedCube(x, y, z); }
        @Override public Cube getCubeFromBlockCoords(BlockPos pos) { return getCubeFromCubeCoords(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4); }
        @Override public int getEffectiveHeight(int x, int z) { return getChunk(x >> 4, z >> 4).getHeightValue(x & 15, z & 15); }
        @Override public boolean isBlockColumnLoaded(BlockPos pos) { return isBlockColumnLoaded(pos, false); }
        @Override public boolean isBlockColumnLoaded(BlockPos pos, boolean empty) { return providerFixture.getLoadedColumn(pos.getX() >> 4, pos.getZ() >> 4) != null; }
        @Override public boolean testForCubes(CubePos first, CubePos last, Predicate<? super ICube> test) { throw new AssertionError("Unexpected region request"); }
        @Override public Chunk getChunk(int x, int z) { return providerFixture.provideColumn(x, z); }
        @Override public boolean checkLightFor(EnumSkyBlock type, BlockPos pos) { return managerFixture.checkLightFor(type, pos); }
        @Override public void notifyLightSet(BlockPos pos) { lightNotifications.add(pos.toImmutable()); }
        @Override public IBlockState getBlockState(BlockPos pos) {
            Cube cube = getCubeFromBlockCoords(pos);
            if (cube == null) throw new AssertionError("Unloaded block read");
            return cube.getBlockState(pos);
        }
    }
}
