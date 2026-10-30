package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.util.Box;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TestGeneratorBuildPool {
    private int threads;

    @Before public void remember() { threads = CubicChunksConfig.generatorBuildThreads; }

    @After public void restore() { CubicChunksConfig.generatorBuildThreads = threads; }

    @Test public void theGeneratorHoldsThePoolUntilTheWorldCloses() throws InterruptedException {
        CubicChunksConfig.generatorBuildThreads = 2;
        PoolGenerator generator = new PoolGenerator();
        GeneratorBuildPool build = GeneratorBuildPool.open(generator, "test");
        ForkJoinPool pool = build.pool();
        assertNotNull(pool);
        assertEquals(2, pool.getParallelism());
        assertEquals(1, generator.offered.size());
        assertSame(pool, generator.offered.get(0));

        // Its threads are named for the world, daemons, and below normal priority.
        Callable<Thread> current = Thread::currentThread;
        Thread worker = pool.submit(current).join();
        assertTrue(worker.getName(), worker.getName().startsWith("Cubature Build test #"));
        assertTrue(worker.isDaemon());
        assertEquals(Thread.NORM_PRIORITY - 1, worker.getPriority());

        build.close();
        assertEquals(2, generator.offered.size());
        assertNull("The pool is withdrawn before it stops", generator.offered.get(1));
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test public void blockedTasksWaitOnceThePoolHoldsTwiceItsTarget() throws InterruptedException {
        CubicChunksConfig.generatorBuildThreads = 2;
        PoolGenerator generator = new PoolGenerator();
        GeneratorBuildPool build = GeneratorBuildPool.open(generator, "test");
        ForkJoinPool pool = build.pool();
        assertNotNull(pool);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicInteger finished = new AtomicInteger();
        for (int i = 0; i < 12; i++) {
            pool.execute(() -> {
                gate.join();
                finished.incrementAndGet();
            });
        }
        // Blocked threads may be replaced, but never past the maximum.
        int most = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            most = Math.max(most, pool.getPoolSize());
            Thread.sleep(5);
        }
        assertTrue("The pool grew to " + most + " threads", most <= GeneratorBuildPool.maximum(2));
        gate.complete(null);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (finished.get() < 12 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(12, finished.get());
        build.close();
    }

    @Test public void aZeroTargetOffersNoPool() {
        CubicChunksConfig.generatorBuildThreads = 0;
        PoolGenerator generator = new PoolGenerator();
        GeneratorBuildPool build = GeneratorBuildPool.open(generator, "test");
        assertNull(build.pool());
        assertEquals(1, generator.offered.size());
        assertNull(generator.offered.get(0));
        build.close();
    }

    @Test public void theDefaultTargetIsAQuarterOfTheProcessorsOneToFour() {
        CubicChunksConfig.generatorBuildThreads = -1;
        int expected = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        assertEquals(expected, GeneratorBuildPool.threads());
    }

    private static final class PoolGenerator implements ICubeGenerator {
        final List<ForkJoinPool> offered = new ArrayList<>();

        @Override public void useBuildPool(ForkJoinPool pool) { offered.add(pool); }

        @Override public CubePrimer generateCube(int cubeX, int cubeY, int cubeZ) { throw new AssertionError("not used"); }
        @Override public void generateColumn(Chunk column) { throw new AssertionError("not used"); }
        @Override public void populate(ICube cube) { throw new AssertionError("not used"); }
        @Override public Box getFullPopulationRequirements(ICube cube) { throw new AssertionError("not used"); }
        @Override public Box getPopulationPregenerationRequirements(ICube cube) { throw new AssertionError("not used"); }
        @Override public void recreateStructures(ICube cube) { }
        @Override public void recreateStructures(Chunk column) { }
        @Override public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) { throw new AssertionError("not used"); }
        @Override public BlockPos getClosestStructure(String name, BlockPos pos, boolean findUnexplored) { throw new AssertionError("not used"); }
    }
}
