package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator.GeneratorReadyState;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import net.minecraft.util.math.ChunkPos;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * Off-thread cube generation and column preparation for a generator that declares
 * {@link ICubeGenerator#supportsConcurrentCubeGeneration()} (pure and thread-safe). Only polls that
 * ask to schedule (delivery requests and arrivals) hand work to the workers; every other request
 * generates synchronously as before.
 */
public final class ConcurrentGeneration {
    private final ICubeGenerator generator;
    private final ThreadPoolExecutor workers;
    private final GenerationQueue<CubePos, CubePrimer> cubes;
    private final GenerationQueue<ChunkPos, Boolean> columns;
    private final AtomicBoolean reported = new AtomicBoolean();

    private ConcurrentGeneration(ICubeGenerator generator, int threads, int capacity, String world) {
        this.generator = generator;
        AtomicInteger counter = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "Cubature Generation " + world + " #" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        this.workers.allowCoreThreadTimeOut(true);
        this.cubes = new GenerationQueue<>(workers,
                pos -> generator.tryGenerateCube(pos.getX(), pos.getY(), pos.getZ(), new CubePrimer(), true).orElse(null),
                this::report, capacity);
        this.columns = new GenerationQueue<>(workers, pos -> {
            generator.prepareColumn(pos.x, pos.z);
            return Boolean.TRUE;
        }, this::report, capacity);
    }

    /** Null unless the generator declares concurrent generation and the worker count is not zero. */
    @Nullable
    public static ConcurrentGeneration create(ICubeGenerator generator, String world) {
        if (!generator.supportsConcurrentCubeGeneration()) return null;
        int threads = CubicChunksConfig.concurrentGenerationThreads;
        if (threads < 0) threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        if (threads == 0) return null;
        CubicChunks.LOGGER.info("Cubature concurrent generation for {}: {} workers, {} queued per kind", world, threads,
                CubicChunksConfig.concurrentGenerationQueue);
        return new ConcurrentGeneration(generator, threads, CubicChunksConfig.concurrentGenerationQueue, world);
    }

    public boolean saturated() { return cubes.saturated() || columns.saturated(); }

    /**
     * With {@code schedule}, hands the column to the workers if they do not hold it yet and reports
     * WAITING until they have prepared it; without, READY unless they are preparing it now. On READY
     * a prepared column is warm in the generator's own cache, and its marker is spent.
     */
    public GeneratorReadyState pollColumn(int columnX, int columnZ, boolean schedule) {
        GeneratorReadyState own = generator.pollAsyncColumnGenerator(columnX, columnZ);
        if (own != GeneratorReadyState.READY) return own;
        ChunkPos pos = new ChunkPos(columnX, columnZ);
        if (columns.poll(pos, schedule) != GenerationQueue.State.READY) return GeneratorReadyState.WAITING;
        columns.take(pos);
        return GeneratorReadyState.READY;
    }

    /** As {@link #pollColumn}; on READY the caller takes the result with {@link #takeCube}, or generates now. */
    public GeneratorReadyState pollCube(int cubeX, int cubeY, int cubeZ, boolean schedule) {
        GeneratorReadyState own = generator.pollAsyncCubeGenerator(cubeX, cubeY, cubeZ);
        if (own != GeneratorReadyState.READY) return own;
        return cubes.poll(new CubePos(cubeX, cubeY, cubeZ), schedule) == GenerationQueue.State.READY
                ? GeneratorReadyState.READY : GeneratorReadyState.WAITING;
    }

    /** The cube generated off-thread, or null when the caller must generate it now. */
    @Nullable
    public CubePrimer takeCube(int cubeX, int cubeY, int cubeZ) {
        return cubes.take(new CubePos(cubeX, cubeY, cubeZ));
    }

    public void close() {
        cubes.close();
        columns.close();
        workers.shutdownNow();
    }

    private void report(Throwable failure) {
        if (reported.compareAndSet(false, true))
            CubicChunks.LOGGER.warn("Cubature concurrent generation failed; the server thread regenerates synchronously", failure);
    }
}
