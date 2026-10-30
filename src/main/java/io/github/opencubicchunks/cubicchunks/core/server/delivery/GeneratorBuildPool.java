package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * A world's build pool: a ForkJoinPool offered to its generator through {@link ICubeGenerator#useBuildPool} for the
 * generator's own parallel work, so that work stays inside the worker budget instead of the JVM's common pool. Its
 * target is {@code generatorBuildThreads}, by default a quarter of the processors, 1 to 4, as for the generation
 * workers. A thread that blocks on a join is replaced, so the threads running stay at the target, but the pool never
 * holds more than twice the target: past that, a blocked task waits (saturate) instead of adding a thread or failing.
 * A generator keeps its own loops progressing then (Riven's caller always works its loop). Threads are daemons below
 * normal priority, as generation workers are, and idle ones end after 30 seconds.
 */
public final class GeneratorBuildPool {
    private final ICubeGenerator generator;
    @Nullable private final ForkJoinPool pool;

    private GeneratorBuildPool(ICubeGenerator generator, @Nullable ForkJoinPool pool) {
        this.generator = generator;
        this.pool = pool;
    }

    /** Opens the pool and offers it to the generator, or offers none when the target is zero. */
    public static GeneratorBuildPool open(ICubeGenerator generator, String world) {
        int threads = threads();
        ForkJoinPool pool = threads == 0 ? null : create(threads, world);
        if (pool != null) CubicChunks.LOGGER.info("Cubature build pool for {}: {} threads", world, threads);
        generator.useBuildPool(pool);
        return new GeneratorBuildPool(generator, pool);
    }

    static int threads() {
        int threads = CubicChunksConfig.generatorBuildThreads;
        return threads < 0 ? Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)) : threads;
    }

    static int maximum(int threads) {
        return 2 * threads;
    }

    private static ForkJoinPool create(int threads, String world) {
        AtomicInteger counter = new AtomicInteger();
        return new ForkJoinPool(threads, owner -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
            thread.setName("Cubature Build " + world + " #" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }, null, false, threads, maximum(threads), 1, pool -> true, 30, TimeUnit.SECONDS);
    }

    @Nullable ForkJoinPool pool() {
        return pool;
    }

    /** Withdraws the pool from the generator, then stops it; work already queued still finishes. */
    public void close() {
        generator.useBuildPool(null);
        if (pool != null) pool.shutdown();
    }
}
