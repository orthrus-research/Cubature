package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.util.Box;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.util.XYZMap;
import io.github.opencubicchunks.cubicchunks.api.world.CubeEvent;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.api.world.IHeightMap;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeClassification;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.lighting.ILightingManager;
import io.github.opencubicchunks.cubicchunks.core.lighting.LightingManager;
import io.github.opencubicchunks.cubicchunks.core.lighting.SurfaceHeightListener;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.ICubeIO;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge.AsyncWorldIOExecutor;
import io.github.opencubicchunks.cubicchunks.core.server.delivery.ConcurrentGeneration;
import io.github.opencubicchunks.cubicchunks.core.server.delivery.MaintenanceState;
import io.github.opencubicchunks.cubicchunks.core.world.IColumnInternal;
import io.github.opencubicchunks.cubicchunks.core.world.ServerHeightMap;
import io.github.opencubicchunks.cubicchunks.core.world.cube.BlankCube;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.IEventListener;
import net.minecraftforge.fml.common.eventhandler.ListenerList;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Actual provider/lifecycle paths on named classes; launch transforms and disk readback are native checks. */
public class TestCubeProviderClassification {
    @BeforeClass public static void plainLoaderPreflight() throws Exception {
        Bootstrap.register();
    }

    @Test public void cachedAndLoadOnlyMissesDoNotClassifyOrGenerate() throws Exception {
        ClassifiedGenerator g = new ClassifiedGenerator();
        try (Fixture f = new Fixture(g, false)) {
            assertNull(f.provider.getCube(0, 4, 0, Requirement.GET_CACHED));
            assertEquals(0, f.io.reads);
            assertNull(f.provider.getCube(0, 4, 0, Requirement.LOAD));
            assertEquals(1, f.io.reads);
            assertEquals(0, g.classifications);
            assertEquals(0, g.generated.get());
            assertEquals(0, f.loads);
        }
    }

    @Test public void restoredStateAndLoadEventReentryWinOverEmptyClassification() throws Exception {
        ClassifiedGenerator g = new ClassifiedGenerator();
        try (Fixture f = new Fixture(g, false)) {
            CubePrimer primer = new CubePrimer(); primer.setBlockState(1, 1, 1, Blocks.STONE.getDefaultState());
            Cube saved = new Cube(f.column, 4, primer); saved.markSaved(); f.io.saved = saved; f.expectedDirty = false; f.expectedSaving = false;
            assertSame(saved, f.provider.getCube(0, 4, 0, Requirement.GENERATE));
            assertSame(saved, f.provider.getCube(0, 4, 0, Requirement.GET_CACHED));
            assertEquals(Blocks.STONE.getDefaultState(), saved.getBlockState(new BlockPos(1, 65, 1)));
            assertEquals(1, f.io.reads); assertEquals(1, f.io.syncLoads);
            assertEquals(0, g.classifications); assertEquals(1, f.entries); assertEquals(1, f.loads);
        }
    }

    @Test public void emptyCubesUseTheNormalDirtyLoadLifecycleForEveryTiming() throws Exception {
        for (int timing = 0; timing < 3; timing++) try (Fixture f = new Fixture(new ClassifiedGenerator(), timing == 2)) {
            Cube cube = f.request(timing, Requirement.GENERATE);
            assertEquals(Cube.class, cube.getClass()); assertNull(cube.getStorage()); assertNull(cube.getBiomeArray());
            assertTrue(cube.needsSaving()); assertFalse(cube.isPopulated()); assertFalse(cube.isInitialLightingDone());
            assertSame(cube, f.request(timing, Requirement.GENERATE));
            assertEquals(1, ((ClassifiedGenerator) f.generator).classifications);
            assertEquals(0, f.generator.generated.get()); assertEquals(0, f.generator.polled);
            assertEquals(1, f.entries); assertEquals(1, f.loads); assertEquals(1, f.column.cubes.size());
            if (f.maintenance != null) assertEquals(1, f.maintenance.cubes().size());
        }
    }

    @Test public void regenerableEmptyCubesStayUnsavedUntilSomethingChangesThem() throws Exception {
        for (int change = 0; change < 4; change++) {
            ClassifiedGenerator g = new ClassifiedGenerator(); g.regenerable = true;
            try (Fixture f = new Fixture(g, false)) {
                f.expectedSaving = false;
                Cube cube = f.request(0, Requirement.POPULATE);
                assertSame(cube, f.request(0, Requirement.LIGHT)); f.world.manager.processUpdates();
                assertTrue(cube.isFullyPopulated()); assertTrue(cube.isInitialLightingDone()); assertEquals(1, g.populated);
                assertEquals(true, get(cube, Cube.class, "isModified"));
                assertFalse(cube.needsSaving());
                switch (change) {
                    case 0 -> cube.setBiome(0, 0, 0, Biomes.PLAINS);
                    case 1 -> ((LightingManager.CubeLightData) cube.getCubeLightData()).neighborLightChecksSky = 1;
                    case 2 -> cube.setStorage(new ExtendedBlockStorage(64, true));
                    default -> {
                        // Once a copy is stored it must stay current: the cube saves like any other.
                        cube.markSaved(); assertFalse(cube.needsSaving());
                        cube.markDirty();
                    }
                }
                assertTrue("change " + change, cube.needsSaving());
            }
        }
    }

    @Test public void generatedCubesAskWhetherTheyAreRegenerableWhenSavingIsConsidered() throws Exception {
        ClassifiedGenerator g = new ClassifiedGenerator();
        g.classification = ICubeClassification.CubeClass.UNKNOWN; g.forbid = false; g.biomes = false;
        try (Fixture f = new Fixture(g, false)) {
            Cube cube = f.request(0, Requirement.GENERATE);
            assertEquals(1, g.generated.get()); assertNull(cube.getStorage()); assertNull(cube.getBiomeArray());
            // Unknown at generation; once its neighbours are held the generator can answer.
            assertTrue(cube.needsSaving());
            g.regenerable = true;
            assertFalse(cube.needsSaving());
            // A true answer stands: classification only ever certifies more.
            g.regenerable = false;
            assertFalse(cube.needsSaving());
            cube.setBiome(0, 0, 0, Biomes.PLAINS);
            assertTrue(cube.needsSaving());
        }
    }

    @Test public void unknownDetailedAndAbsentCapabilityKeepReadinessGenerationAndPrimerReset() throws Exception {
        for (int mode = 0; mode < 3; mode++) for (int timing = 0; timing < 3; timing++) {
            Generator g = mode == 2 ? new Generator() : new ClassifiedGenerator();
            if (g instanceof ClassifiedGenerator c) { c.classification = mode == 0 ? ICubeClassification.CubeClass.UNKNOWN : ICubeClassification.CubeClass.DETAILED; c.forbid = false; }
            try (Fixture f = new Fixture(g, false)) {
                g.ready = ICubeGenerator.GeneratorReadyState.WAITING;
                if (timing != 2) { assertTrue(f.request(timing, Requirement.GENERATE) instanceof BlankCube); assertEquals(0, g.generated.get()); }
                g.ready = ICubeGenerator.GeneratorReadyState.READY;
                Cube cube = f.request(timing, Requirement.GENERATE);
                assertNotNull(cube.getBiomeArray()); assertEquals(1, g.generated.get()); assertEquals(timing == 2, g.forced);
                assertFalse(f.primer.hasBiomes()); assertEquals(1, f.loads);
            }
        }
    }

    @Test public void emptyInstallationStillWaitsForPopulationAndCompletesActualLighting() throws Exception {
        ClassifiedGenerator g = new ClassifiedGenerator();
        try (Fixture f = new Fixture(g, false)) {
            g.populationReady = ICubeGenerator.GeneratorReadyState.WAITING;
            assertTrue(f.request(0, Requirement.LIGHT) instanceof BlankCube);
            Cube cube = f.provider.getLoadedCube(0, 4, 0); assertNotNull(cube); assertEquals(0, g.populated);
            g.populationReady = ICubeGenerator.GeneratorReadyState.READY;
            assertSame(cube, f.request(0, Requirement.POPULATE));
            assertTrue(cube.isPopulated()); assertTrue(cube.isFullyPopulated()); assertEquals(1, g.populated);
            assertFalse(cube.isInitialLightingDone());
            assertSame(cube, f.request(0, Requirement.LIGHT)); f.world.manager.processUpdates();
            assertTrue(cube.isInitialLightingDone()); assertTrue(cube.isSurfaceTracked());
            assertEquals(1, f.loads); assertEquals(1, g.classifications); assertEquals(0, g.generated.get());
        }
    }

    @Test public void emptyInstallationDoesNotAwaitAnObsoleteActiveWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), installed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int oldThreads = CubicChunksConfig.concurrentGenerationThreads; CubicChunksConfig.concurrentGenerationThreads = 1;
        Thread owner = new Thread(() -> {
            ClassifiedGenerator g = new ClassifiedGenerator(); g.classification = ICubeClassification.CubeClass.UNKNOWN; g.forbid = false;
            g.started = started; g.release = release;
            try (Fixture f = new Fixture(g, false)) {
                f.concurrent = ConcurrentGeneration.create(g, "provider-retirement-test");
                set(f.provider, CubeProviderServer.class, "concurrentGeneration", f.concurrent);
                assertTrue(f.request(1, Requirement.GENERATE) instanceof BlankCube); await(started);
                g.classification = ICubeClassification.CubeClass.EMPTY; g.forbid = true;
                Cube cube = f.request(1, Requirement.GENERATE); assertEquals(Cube.class, cube.getClass()); installed.countDown();
                await(release);
                // Waiting for the obsolete worker polls readiness; only a second generation is forbidden.
                g.forbid = false;
                f.concurrent.pollCube(0, 4, 0, false);
                assertNull(f.concurrent.takeCube(0, 4, 0)); assertSame(cube, f.provider.getLoadedCube(0, 4, 0));
                assertEquals(1, f.loads); assertEquals(1, g.generated.get());
            } catch (Throwable error) { failure.set(error); }
        }, "provider-classification-owner");
        owner.setDaemon(true);
        try { owner.start(); assertTrue(started.await(10, TimeUnit.SECONDS)); assertTrue(installed.await(10, TimeUnit.SECONDS)); }
        finally { release.countDown(); owner.join(10000); CubicChunksConfig.concurrentGenerationThreads = oldThreads; }
        assertFalse(owner.isAlive()); assertNull(failure.get());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static Object get(Object object, Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private static void set(Object object, Class<?> type, String name, Object value) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static final class Fixture implements AutoCloseable {
        final FixtureWorld world = allocate(FixtureWorld.class);
        final CubeProviderServer provider = allocate(CubeProviderServer.class);
        final Generator generator; final Backend io = new Backend(); final CubePrimer primer = new CubePrimer();
        final Column column; final MaintenanceState maintenance; final IEventListener observer; final int bus;
        ConcurrentGeneration concurrent; boolean expectedDirty = true, expectedSaving = true; int entries, loads;
        Fixture(Generator generator, boolean incremental) throws Exception {
            this.generator = generator; world.fixture = this;
            set(world, World.class, "provider", new WorldProviderSurface()); set(world, World.class, "profiler", new Profiler());
            set(world, World.class, "worldInfo", new WorldInfo(new NBTTagCompound()));
            world.listener = allocate(Listener.class); world.manager = new LightingManager(world); column = new Column(world);
            maintenance = incremental ? new MaintenanceState(false) : null;
            set(provider, ChunkProviderServer.class, "world", world);
            Long2ObjectOpenHashMap<Chunk> columns = new Long2ObjectOpenHashMap<>(); columns.put(0L, column);
            set(provider, ChunkProviderServer.class, "loadedChunks", columns);
            set(provider, CubeProviderServer.class, "worldServer", world); set(provider, CubeProviderServer.class, "cubeIO", io);
            set(provider, CubeProviderServer.class, "cubeGen", generator); set(provider, CubeProviderServer.class, "cubePrimer", primer);
            set(provider, CubeProviderServer.class, "cubeMap", new XYZMap<Cube>(0.7f, 16));
            set(provider, CubeProviderServer.class, "loadingColumns", new LoadingColumnContext());
            set(provider, CubeProviderServer.class, "emptyCube", new BlankCube(column));
            set(provider, CubeProviderServer.class, "maintenance", maintenance);
            set(provider, CubeProviderServer.class, "profiler", world.profiler);
            set(provider, CubeProviderServer.class, "deliveryMode", CubicChunksConfig.DeliveryMode.BASELINE);
            bus = (int) get(MinecraftForge.EVENT_BUS, EventBus.class, "busID");
            observer = event -> {
                if (!(event instanceof CubeEvent.Load) || ((CubeEvent.Load) event).getCube().getWorld() != world) return;
                Cube cube = (Cube) ((CubeEvent.Load) event).getCube(); loads++;
                assertTrue(cube.isCubeLoaded()); assertSame(cube, provider.getLoadedCube(0, 4, 0)); assertEquals(0, generator.populated);
                assertSame(cube, provider.getCube(0, 4, 0, Requirement.GENERATE));
            };
            new CubeEvent.Load(new Cube(column, 4)).getListenerList().register(bus, EventPriority.NORMAL, observer);
        }
        Cube request(int timing, Requirement req) {
            return timing == 0 ? provider.getCube(0, 4, 0, req) : timing == 1 ? provider.getCubeForDelivery(0, 4, 0, req) : provider.getCubeNow(0, 4, 0, req);
        }
        void enteringLoad() {
            Cube cube = provider.getLoadedCube(0, 4, 0); assertNotNull(cube); assertSame(cube, column.cubes.iterator().next());
            assertFalse(cube.isCubeLoaded()); assertEquals(expectedSaving, cube.needsSaving());
            try { assertEquals(expectedDirty, get(cube, Cube.class, "isModified")); } catch (Exception e) { throw new AssertionError(e); }
            entries++;
        }
        @Override public void close() throws Exception {
            ListenerList.unregisterAll(bus, observer);
            try { if (concurrent != null) concurrent.close(); } finally { AsyncWorldIOExecutor.shutdownWorldBlocking(world); }
        }
    }

    private static final class Backend implements ICubeIO {
        Cube saved; int reads, syncLoads;
        @Override public PartialData<ICube> loadCubeNbt(Chunk column, int y) { reads++; return new PartialData<>(saved, new NBTTagCompound()); }
        @Override public void loadCubeAsyncPart(PartialData<ICube> data, Chunk column, int y) { }
        @Override public void loadCubeSyncPart(PartialData<ICube> data) { syncLoads++; }
        @Override public PartialData<Chunk> loadColumnNbt(int x, int z) { throw new AssertionError("column I/O"); }
        @Override public void loadColumnAsyncPart(PartialData<Chunk> data, int x, int z) { throw new AssertionError("column I/O"); }
        @Override public void loadColumnSyncPart(PartialData<Chunk> data) { throw new AssertionError("column I/O"); }
        @Override public void saveColumn(Chunk column) { throw new AssertionError("save"); }
        @Override public void saveCube(Cube cube) { throw new AssertionError("save"); }
        @Override public boolean cubeExists(int x, int y, int z) { return saved != null; }
        @Override public boolean columnExists(int x, int z) { return true; }
        @Override public int getPendingColumnCount() { return 0; }
        @Override public int getPendingCubeCount() { return 0; }
        @Override public boolean writeNextIO() { throw new AssertionError("writer"); }
        @Override public void flush() { } @Override public void close() { }
    }

    private static class Generator implements ICubeGenerator {
        final AtomicInteger generated = new AtomicInteger(); int polled, populated; boolean forced;
        volatile boolean forbid; boolean biomes = true; CountDownLatch started, release;
        GeneratorReadyState ready = GeneratorReadyState.READY, populationReady = GeneratorReadyState.READY;
        @Override public boolean supportsConcurrentCubeGeneration() { return true; }
        @Override public GeneratorReadyState pollAsyncCubeGenerator(int x, int y, int z) { if (forbid) throw new AssertionError("generation readiness"); polled++; return ready; }
        @Override public GeneratorReadyState pollAsyncCubePopulator(int x, int y, int z) { return populationReady; }
        @Override public Optional<CubePrimer> tryGenerateCube(int x, int y, int z, CubePrimer primer, boolean force) {
            if (forbid) throw new AssertionError("generation"); generated.incrementAndGet(); forced = force;
            if (started != null) { started.countDown(); await(release); }
            if (biomes) primer.setBiome(0, 0, 0, Biomes.PLAINS);
            return Optional.of(primer);
        }
        @Override public CubePrimer generateCube(int x, int y, int z) { throw new AssertionError("legacy generation"); }
        @Override public void generateColumn(Chunk column) { throw new AssertionError("column generation"); }
        @Override public void populate(ICube cube) { populated++; }
        @Override public Box getFullPopulationRequirements(ICube cube) { return NO_REQUIREMENT; }
        @Override public Box getPopulationPregenerationRequirements(ICube cube) { return NO_REQUIREMENT; }
        @Override public void recreateStructures(ICube cube) { } @Override public void recreateStructures(Chunk column) { }
        @Override public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) { throw new AssertionError("creatures"); }
        @Override public BlockPos getClosestStructure(String name, BlockPos pos, boolean unexplored) { throw new AssertionError("structure"); }
    }
    private static final class ClassifiedGenerator extends Generator implements ICubeClassification {
        volatile CubeClass classification = CubeClass.EMPTY; int classifications; boolean regenerable;
        ClassifiedGenerator() { forbid = true; }
        @Override public CubeClass cubeClass(int x, int y, int z) { classifications++; return classification; }
        @Override public boolean regenerable(int x, int y, int z) { return regenerable; }
        @Override public SurfaceBand surfaceBand(int x, int z) { throw new AssertionError("surface band"); }
    }

    private static final class Column extends Chunk implements IColumnInternal {
        final ServerHeightMap heights; final Set<Cube> cubes = new HashSet<>(); final Set<ICube> staging = new HashSet<>();
        Column(World world) { super(world, 0, 0); heights = new ServerHeightMap(getHeightMap()); for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) heights.onOpacityChange(x, 63, z, 255); }
        @Override public int getX() { return x; } @Override public int getZ() { return z; }
        @Override public IHeightMap getOpacityIndex() { return heights; }
        @Override public int getTopYWithStaging(int x, int z) { return heights.getTopBlockY(x, z); }
        @Override public int getHeightValue(int x, int y, int z) { return heights.getTopBlockY(x, z) + 1; }
        @Override public boolean shouldTick() { return false; }
        @Override public Collection<? extends ICube> getLoadedCubes() { return cubes; }
        @Override public Iterable<? extends ICube> getLoadedCubes(int first, int last) { return first <= 4 && last >= 4 ? cubes : Collections.emptyList(); }
        @Override public ICube getLoadedCube(int y) { return y == 4 && !cubes.isEmpty() ? cubes.iterator().next() : null; }
        @Override public ICube getCube(int y) { throw new AssertionError("column cube load"); }
        @Override public void addCube(ICube cube) { cubes.add((Cube) cube); }
        @Override public ICube removeCube(int y) { throw new AssertionError("remove"); }
        @Override public boolean hasLoadedCubes() { return !cubes.isEmpty(); }
        @Override public void preCacheCube(ICube cube) { throw new AssertionError("precache"); }
        @Override public ChunkPrimer getCompatGenerationPrimer() { throw new AssertionError("compatibility"); }
        @Override public void addToStagingHeightmap(ICube cube) { staging.add(cube); }
        @Override public void removeFromStagingHeightmap(ICube cube) { staging.remove(cube); }
    }
    private static final class Listener extends PlayerChunkMap implements SurfaceHeightListener {
        private Listener() { super(null); } @Override public void heightUpdated(int x, int z) { }
    }
    private static final class FixtureWorld extends WorldServer implements ICubicWorldInternal {
        Fixture fixture; LightingManager manager; Listener listener;
        private FixtureWorld() { super(null, null, null, 0, null); }
        @Override public void addTileEntities(Collection<TileEntity> values) { assertTrue(values.isEmpty()); fixture.enteringLoad(); super.addTileEntities(values); }
        @Override public void loadEntities(Collection<Entity> values) { assertTrue(values.isEmpty()); super.loadEntities(values); }
        @Override public CubeProviderServer getChunkProvider() { return fixture.provider; }
        @Override public CubeProviderServer getCubeCache() { return fixture.provider; }
        @Override public PlayerChunkMap getPlayerChunkMap() { return listener; }
        @Override public ILightingManager getLightingManager() { return manager; }
        @Override public boolean isCubicWorld() { return true; }
        @Override public int getMinHeight() { return -256; } @Override public int getMaxHeight() { return 512; }
        @Override public int getMinGenerationHeight() { return -256; } @Override public int getMaxGenerationHeight() { return 512; }
        @Override public void tickCubicWorld() { throw new AssertionError("world tick"); }
        @Override public Cube getCubeFromCubeCoords(int x, int y, int z) { return fixture.provider.getLoadedCube(x, y, z); }
        @Override public Cube getCubeFromBlockCoords(BlockPos pos) { return getCubeFromCubeCoords(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4); }
        @Override public int getEffectiveHeight(int x, int z) { return 64; }
        @Override public boolean isBlockColumnLoaded(BlockPos pos) { return isBlockColumnLoaded(pos, false); }
        @Override public boolean isBlockColumnLoaded(BlockPos pos, boolean empty) { return pos.getX() >> 4 == 0 && pos.getZ() >> 4 == 0; }
        @Override public boolean testForCubes(CubePos first, CubePos last, Predicate<? super ICube> test) { throw new AssertionError("region"); }
        @Override public Chunk getChunk(int x, int z) { if (x != 0 || z != 0) throw new AssertionError("column load"); return fixture.column; }
    }
}
