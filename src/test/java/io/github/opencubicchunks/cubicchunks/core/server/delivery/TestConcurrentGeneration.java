package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.util.Box;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator.GeneratorReadyState;
import io.github.opencubicchunks.cubicchunks.api.worldgen.IRetainedColumnPreparation;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TestConcurrentGeneration {
    private int threads;
    private ConcurrentGeneration generation;

    @Before public void oneWorker() {
        threads = CubicChunksConfig.concurrentGenerationThreads;
        CubicChunksConfig.concurrentGenerationThreads = 1;
    }

    @After public void restore() {
        if (generation != null) generation.close();
        CubicChunksConfig.concurrentGenerationThreads = threads;
    }

    @Test public void onlyAPollThatSchedulesHandsWorkToTheWorkers() throws InterruptedException {
        PreparingGenerator generator = new PreparingGenerator();
        generation = ConcurrentGeneration.create(generator, "test");
        assertNotNull(generation);

        // Without scheduling the caller generates now: nothing reaches the workers.
        assertEquals(GeneratorReadyState.READY, generation.pollColumn(1, 2, false));
        assertEquals(0, generator.prepared.get());

        assertEquals(GeneratorReadyState.WAITING, generation.pollColumn(1, 2, true));
        GeneratorReadyState state = GeneratorReadyState.WAITING;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (state == GeneratorReadyState.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
            state = generation.pollColumn(1, 2, false);
        }
        assertEquals(GeneratorReadyState.READY, state);
        assertEquals(1, generator.prepared.get());

        // The prepared marker is spent once; a later poll that does not schedule adds no work.
        assertEquals(GeneratorReadyState.READY, generation.pollColumn(1, 2, false));
        assertEquals(1, generator.prepared.get());
    }

    @Test public void arrivalPreparationRetainsItsLeaseWithoutSpendingReadiness() throws InterruptedException {
        RetainedGenerator generator = new RetainedGenerator();
        generation = ConcurrentGeneration.create(generator, "retained-test");
        assertNotNull(generation);
        assertTrue(generation.retainsArrivalColumns());
        UUID owner = new UUID(0, 1);
        generation.retainArrivalColumn(owner, 1, 2);
        ConcurrentGeneration.ArrivalStatus status = generation.arrivalStatus(1, 2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (status.state() == ConcurrentGeneration.ArrivalState.PENDING && System.nanoTime() < deadline) {
            Thread.sleep(1);
            status = generation.arrivalStatus(1, 2);
        }
        assertEquals(ConcurrentGeneration.ArrivalState.READY, status.state());
        assertEquals(ConcurrentGeneration.ArrivalState.READY, generation.arrivalStatus(1, 2).state());
        assertEquals(1, generator.retained.get());
        assertEquals(0, generator.prepared.get());
        assertEquals(0, generator.released.get());
        generation.releaseArrivalColumn(owner);
        assertEquals(1, generator.released.get());
        generation.close();
        assertEquals(1, generator.released.get());
    }

    @Test public void disabledWorkersDoNotCreateRetainedArrivalWork() {
        CubicChunksConfig.concurrentGenerationThreads = 0;
        RetainedGenerator generator = new RetainedGenerator();
        assertNull(ConcurrentGeneration.create(generator, "disabled-test"));
        assertEquals(0, generator.retained.get());
    }

    private static final class RetainedGenerator extends PreparingGenerator implements IRetainedColumnPreparation {
        final AtomicInteger retained = new AtomicInteger(), released = new AtomicInteger();
        @Override public ColumnLease prepareRetainedColumn(int columnX, int columnZ) {
            retained.incrementAndGet();
            return released::incrementAndGet;
        }
    }

    private static class PreparingGenerator implements ICubeGenerator {
        final AtomicInteger prepared = new AtomicInteger();

        @Override public boolean supportsConcurrentCubeGeneration() { return true; }
        @Override public void prepareColumn(int columnX, int columnZ) { prepared.incrementAndGet(); }

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
