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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TestGeneratorBuildPool {
    private int threads;
    private final Object server = new Object();
    private GeneratorBuildPool.Registry<Object> worlds;

    @Before public void remember() {
        threads = CubicChunksConfig.generatorBuildThreads;
        worlds = new GeneratorBuildPool.Registry<>();
        worlds.start(server);
    }

    @After public void restore() {
        try { worlds.stop(); }
        finally { CubicChunksConfig.generatorBuildThreads = threads; }
    }

    @Test public void theGeneratorHoldsThePoolUntilTheWorldCloses() throws InterruptedException {
        CubicChunksConfig.generatorBuildThreads = 2;
        PoolGenerator generator = new PoolGenerator();
        GeneratorBuildPool build = worlds.open(server, new Object(), generator, "test");
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
        GeneratorBuildPool build = worlds.open(server, new Object(), generator, "test");
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
        GeneratorBuildPool build = worlds.open(server, new Object(), generator, "test");
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

    @Test public void anEarlyRequestIsAdoptedAndClosedExactlyOnce() {
        CubicChunksConfig.generatorBuildThreads = 2;
        Object world = new Object();
        GeneratorBuildPool early = worlds.get(server, world, "test");
        PoolGenerator generator = new PoolGenerator();
        assertSame(early, worlds.get(server, world, "test"));
        assertSame(early, worlds.open(server, world, generator, "test"));
        assertSame(early, worlds.open(server, world, generator, "test"));
        assertEquals(1, generator.offered.size());
        assertSame(early.pool(), generator.offered.get(0));

        worlds.close(world);
        early.close();
        worlds.close(world);
        worlds.stop();
        assertEquals(2, generator.offered.size());
        assertNull(generator.offered.get(1));
        assertTrue(early.pool().isShutdown());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
    }

    @Test public void equalWorldKeysHaveSeparatePools() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object first = new String("world"), second = new String("world");
        assertEquals(first, second);
        GeneratorBuildPool a = worlds.get(server, first, "a");
        GeneratorBuildPool b = worlds.get(server, second, "b");
        assertNotSame(a, b);
        assertNotSame(a.pool(), b.pool());
        worlds.close(first);
        assertSame(b, worlds.get(server, second, "b"));
        assertFalse(b.pool().isShutdown());
    }

    @Test public void aZeroTargetStillHasOneWorldHolder() {
        CubicChunksConfig.generatorBuildThreads = 0;
        Object world = new Object();
        GeneratorBuildPool early = worlds.get(server, world, "test");
        PoolGenerator generator = new PoolGenerator();
        assertNull(early.pool());
        assertSame(early, worlds.open(server, world, generator, "test"));
        assertSame(early, worlds.get(server, world, "test"));
        assertEquals(1, generator.offered.size());
        assertNull(generator.offered.get(0));
    }

    @Test public void anUnadoptedPoolClosesAndCannotBeReopened() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        GeneratorBuildPool early = worlds.get(server, world, "test");
        worlds.close(world);
        assertTrue(early.pool().isShutdown());
        PoolGenerator generator = new PoolGenerator();
        assertThrows(IllegalStateException.class, () -> worlds.open(server, world, generator, "test"));
        assertTrue(generator.offered.isEmpty());
    }

    @Test public void abandonmentLeavesAnAdoptedPoolUntilWorldClose() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        PoolGenerator generator = new PoolGenerator();
        GeneratorBuildPool holder = worlds.open(server, world, generator, "test");
        worlds.abandon(world);
        worlds.abandon(world);
        assertSame(holder, worlds.get(server, world, "test"));
        assertFalse(holder.pool().isShutdown());
        assertEquals(1, generator.offered.size());
        worlds.close(world);
        assertTrue(holder.pool().isShutdown());
        assertEquals(2, generator.offered.size());
    }

    @Test public void abandonmentRetiresAnUnadoptedPoolOnce() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        GeneratorBuildPool holder = worlds.get(server, world, "test");
        worlds.abandon(world);
        worlds.abandon(world);
        assertTrue(holder.pool().isShutdown());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
        PoolGenerator generator = new PoolGenerator();
        assertThrows(IllegalStateException.class, () -> worlds.open(server, world, generator, "test"));
        assertTrue(generator.offered.isEmpty());
    }

    @Test public void abandonmentBeforeARequestPreventsPoolCreation() {
        Object world = new Object();
        worlds.abandon(world);
        worlds.abandon(world);
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
    }

    @Test public void zeroTargetAbandonmentStillFollowsAdoption() {
        CubicChunksConfig.generatorBuildThreads = 0;
        Object adoptedWorld = new Object(), unadoptedWorld = new Object();
        PoolGenerator generator = new PoolGenerator();
        GeneratorBuildPool adopted = worlds.open(server, adoptedWorld, generator, "adopted");
        worlds.get(server, unadoptedWorld, "unadopted");
        worlds.abandon(adoptedWorld);
        worlds.abandon(unadoptedWorld);
        assertSame(adopted, worlds.get(server, adoptedWorld, "adopted"));
        assertNull(adopted.pool());
        assertEquals(1, generator.offered.size());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, unadoptedWorld, "unadopted"));
        worlds.close(adoptedWorld);
        assertEquals(2, generator.offered.size());
    }

    @Test public void aFailedOfferClosesAfterAbandonmentDuringTheCallback() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        GeneratorBuildPool holder = worlds.get(server, world, "test");
        IllegalStateException failure = new IllegalStateException("offer");
        PoolGenerator generator = new PoolGenerator() {
            @Override public void useBuildPool(ForkJoinPool pool) {
                super.useBuildPool(pool);
                if (pool != null) {
                    worlds.abandon(world);
                    throw failure;
                }
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> worlds.open(server, world, generator, "test")));
        assertTrue(holder.pool().isShutdown());
        assertEquals(2, generator.offered.size());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
    }

    @Test public void abandonmentWaitsForAdoptionWithoutHoldingTheRegistryLock() throws Exception {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        CountDownLatch offerStarted = new CountDownLatch(1), release = new CountDownLatch(1), requested = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        PoolGenerator generator = new PoolGenerator() {
            @Override public void useBuildPool(ForkJoinPool pool) {
                super.useBuildPool(pool);
                assertFalse(Thread.holdsLock(worlds));
                if (pool != null) {
                    offerStarted.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
            }
        };
        Thread adopting = new Thread(() -> {
            try { worlds.open(server, world, generator, "test"); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
        });
        Thread abandoning = new Thread(() -> {
            try { worlds.abandon(world); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
        });
        Thread requesting = new Thread(() -> {
            try { worlds.get(server, new Object(), "other"); requested.countDown(); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
        });
        adopting.start();
        try {
            assertTrue(offerStarted.await(10, TimeUnit.SECONDS));
            abandoning.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (abandoning.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(Thread.State.BLOCKED, abandoning.getState());
            requesting.start();
            assertTrue("Abandonment must not hold the registry while waiting on adoption", requested.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            adopting.join(10000);
            abandoning.join(10000);
            requesting.join(10000);
        }
        assertFalse(adopting.isAlive());
        assertFalse(abandoning.isAlive());
        assertFalse(requesting.isAlive());
        assertNull(failed.get());
        GeneratorBuildPool holder = worlds.get(server, world, "test");
        assertFalse(holder.pool().isShutdown());
        assertEquals(1, generator.offered.size());
    }

    @Test public void abandonmentRejectsAnAdopterThatAlreadyFetchedTheHolder() throws Exception {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        GeneratorBuildPool holder = worlds.get(server, world, "test");
        PoolGenerator generator = new PoolGenerator();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread adopting = new Thread(() -> {
            try { worlds.open(server, world, generator, "test"); }
            catch (Throwable failure) { failed.set(failure); }
        });
        try {
            synchronized (holder) {
                adopting.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (adopting.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, adopting.getState());
                worlds.abandon(world);
            }
        } finally { adopting.join(10000); }
        assertFalse(adopting.isAlive());
        assertTrue(failed.get() instanceof IllegalStateException);
        assertTrue(generator.offered.isEmpty());
        assertTrue(holder.pool().isShutdown());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
    }

    @Test public void aSecondGeneratorCannotReplaceTheOwner() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        PoolGenerator owner = new PoolGenerator(), other = new PoolGenerator();
        GeneratorBuildPool holder = worlds.open(server, world, owner, "test");
        assertThrows(IllegalStateException.class, () -> worlds.open(server, world, other, "test"));
        assertTrue(other.offered.isEmpty());
        assertEquals(1, owner.offered.size());
        assertFalse(holder.pool().isShutdown());
    }

    @Test public void providerCloseRejectsRequestsBeforeTheWorldUnloadEvent() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        PoolGenerator generator = new PoolGenerator();
        GeneratorBuildPool holder = worlds.open(server, world, generator, "test");
        holder.close();
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
        assertTrue(holder.pool().isShutdown());
        assertEquals(2, generator.offered.size());
    }

    @Test public void aFailedOfferRetiresThePoolEvenIfWithdrawalAlsoFails() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        GeneratorBuildPool early = worlds.get(server, world, "test");
        IllegalStateException offer = new IllegalStateException("offer"), withdraw = new IllegalStateException("withdraw");
        PoolGenerator generator = new PoolGenerator() {
            @Override public void useBuildPool(ForkJoinPool pool) {
                super.useBuildPool(pool);
                throw pool == null ? withdraw : offer;
            }
        };
        assertSame(offer, assertThrows(IllegalStateException.class, () -> worlds.open(server, world, generator, "test")));
        assertArrayEquals(new Throwable[] {withdraw}, offer.getSuppressed());
        assertEquals(2, generator.offered.size());
        assertTrue(early.pool().isShutdown());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
    }

    @Test public void theSameOfferAndWithdrawalFailureIsPreserved() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        GeneratorBuildPool early = worlds.get(server, world, "test");
        IllegalStateException failure = new IllegalStateException("callback");
        PoolGenerator generator = new PoolGenerator() {
            @Override public void useBuildPool(ForkJoinPool pool) {
                super.useBuildPool(pool);
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> worlds.open(server, world, generator, "test")));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(2, generator.offered.size());
        assertTrue(early.pool().isShutdown());
    }

    @Test public void serverStopClosesEveryPoolAfterAWithdrawalFails() {
        CubicChunksConfig.generatorBuildThreads = 1;
        IllegalStateException failure = new IllegalStateException("withdraw");
        PoolGenerator broken = new PoolGenerator() {
            @Override public void useBuildPool(ForkJoinPool pool) {
                super.useBuildPool(pool);
                if (pool == null) throw failure;
            }
        };
        GeneratorBuildPool first = worlds.open(server, new Object(), broken, "first");
        GeneratorBuildPool sameFailure = worlds.open(server, new Object(), new PoolGenerator() {
            @Override public void useBuildPool(ForkJoinPool pool) {
                if (pool == null) throw failure;
            }
        }, "same failure");
        PoolGenerator normal = new PoolGenerator();
        GeneratorBuildPool second = worlds.open(server, new Object(), normal, "second");
        GeneratorBuildPool unadopted = worlds.get(server, new Object(), "unadopted");
        assertSame(failure, assertThrows(IllegalStateException.class, worlds::stop));
        assertTrue(first.pool().isShutdown());
        assertTrue(sameFailure.pool().isShutdown());
        assertTrue(second.pool().isShutdown());
        assertTrue(unadopted.pool().isShutdown());
        assertEquals(2, broken.offered.size());
        assertEquals(2, normal.offered.size());
        worlds.stop();
        assertEquals(2, broken.offered.size());
    }

    @Test public void aRetiredServerCannotRequestPoolsAfterRestart() {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object oldWorld = new Object(), newWorld = new Object(), nextServer = new Object();
        GeneratorBuildPool old = worlds.get(server, oldWorld, "old");
        worlds.stop();
        assertTrue(old.pool().isShutdown());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, new Object(), "late"));
        worlds.start(nextServer);
        assertThrows(IllegalStateException.class, () -> worlds.get(server, new Object(), "late"));
        assertThrows(IllegalStateException.class, () -> worlds.get(nextServer, oldWorld, "old"));
        assertNotNull(worlds.get(nextServer, newWorld, "new").pool());
    }

    @Test public void closeAndAbandonRacingAdoptionWithdrawThePoolOnce() throws Exception {
        CubicChunksConfig.generatorBuildThreads = 1;
        Object world = new Object();
        GeneratorBuildPool early = worlds.get(server, world, "test");
        CountDownLatch offerStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        PoolGenerator generator = new PoolGenerator() {
            @Override public void useBuildPool(ForkJoinPool pool) {
                super.useBuildPool(pool);
                if (pool != null) {
                    offerStarted.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
            }
        };
        Thread adopting = new Thread(() -> {
            try { worlds.open(server, world, generator, "test"); }
            catch (Throwable failure) { failed.set(failure); }
        });
        Thread closing = new Thread(() -> {
            try { worlds.close(world); }
            catch (Throwable failure) { failed.set(failure); }
        });
        Thread abandoning = new Thread(() -> {
            try { worlds.abandon(world); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
        });
        adopting.start();
        try {
            assertTrue(offerStarted.await(10, TimeUnit.SECONDS));
            closing.start();
            abandoning.start();
        } finally { release.countDown(); }
        adopting.join(10000);
        closing.join(10000);
        abandoning.join(10000);
        assertFalse(adopting.isAlive());
        assertFalse(closing.isAlive());
        assertFalse(abandoning.isAlive());
        assertNull(failed.get());
        assertEquals(2, generator.offered.size());
        assertNull(generator.offered.get(1));
        assertTrue(early.pool().isShutdown());
        assertThrows(IllegalStateException.class, () -> worlds.get(server, world, "test"));
    }

    private static class PoolGenerator implements ICubeGenerator {
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
