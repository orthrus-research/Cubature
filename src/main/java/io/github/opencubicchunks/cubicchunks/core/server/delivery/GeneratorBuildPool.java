package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

/**
 * A world's build pool: available before biome-provider construction, then offered to its generator through
 * {@link ICubeGenerator#useBuildPool} for its own parallel work, so that work stays inside the worker budget instead
 * of the JVM's common pool. Its
 * target is {@code generatorBuildThreads}, by default a quarter of the processors, 1 to 4, as for the generation
 * workers. A thread that blocks on a join is replaced, so the threads running stay at the target, but the pool never
 * holds more than twice the target: past that, a blocked task waits (saturate) instead of adding a thread or failing.
 * A generator keeps its own loops progressing then (Riven's caller always works its loop). Threads are daemons below
 * normal priority, as generation workers are, and idle ones end after 30 seconds.
 */
public final class GeneratorBuildPool {
    private static final Registry<World> WORLDS = new Registry<>();
    @Nullable private ICubeGenerator generator;
    @Nullable private final ForkJoinPool pool;
    private volatile boolean closed;

    private GeneratorBuildPool(@Nullable ForkJoinPool pool) {
        this.pool = pool;
    }

    /** The early world-type request; the provider later adopts this same holder. */
    @Nullable public static ForkJoinPool request(World world) {
        return WORLDS.get(world.getMinecraftServer(), world, name(world)).pool();
    }

    /** Adopts the world's pool and offers it to the generator, or offers none when the target is zero. */
    public static GeneratorBuildPool open(World world, ICubeGenerator generator) {
        return WORLDS.open(world.getMinecraftServer(), world, generator, name(world));
    }

    /** Closes a requested pool even if world construction never reached the cube provider. */
    public static void close(World world) { WORLDS.close(world); }

    /** Failed early construction retires only a pool the cube provider has not adopted. */
    public static void abandon(World world) { WORLDS.abandon(world); }

    /** A new server may create pools; closed worlds from the previous server stay closed. */
    public static void serverStarting(MinecraftServer server) { WORLDS.start(server); }

    /** Also closes requested pools left behind by failed world construction. */
    public static void serverStopped() { WORLDS.stop(); }

    private static String name(World world) { return "dimension " + world.provider.getDimension(); }

    private static GeneratorBuildPool create(String world) {
        int threads = threads();
        ForkJoinPool pool = threads == 0 ? null : create(threads, world);
        if (pool != null) CubicChunks.LOGGER.info("Cubature build pool for {}: {} threads", world, threads);
        return new GeneratorBuildPool(pool);
    }

    private synchronized void adopt(ICubeGenerator generator) {
        if (closed) throw new IllegalStateException("The world's build pool is closed");
        if (this.generator == generator) return;
        if (this.generator != null) throw new IllegalStateException("The world's build pool already has a generator");
        this.generator = generator;
        try {
            generator.useBuildPool(pool);
        } catch (RuntimeException | Error failure) {
            try { close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
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
    public synchronized void close() {
        if (closed) return;
        closed = true;
        ICubeGenerator owner = generator;
        generator = null;
        try {
            if (owner != null) owner.useBuildPool(null);
        } finally {
            if (pool != null) pool.shutdown();
        }
    }

    /** Shares adoption's lock so a failed early request cannot withdraw a live generator's pool. */
    private synchronized void abandonUnadopted() {
        if (generator == null) close();
    }

    /** Identity keys prevent two dimensions or replacement worlds from sharing a holder. */
    static final class Registry<W> {
        private final IdentityHashMap<W, GeneratorBuildPool> active = new IdentityHashMap<>();
        private final List<WeakReference<W>> closed = new ArrayList<>();
        @Nullable private Object server;

        synchronized GeneratorBuildPool get(Object server, W world, String name) {
            if (server == null || this.server != server || wasClosed(world))
                throw new IllegalStateException("The world's build pool is closed");
            GeneratorBuildPool holder = active.computeIfAbsent(world, key -> create(name));
            if (holder.closed) {
                active.remove(world);
                closed.add(new WeakReference<>(world));
                throw new IllegalStateException("The world's build pool is closed");
            }
            return holder;
        }

        GeneratorBuildPool open(Object server, W world, ICubeGenerator generator, String name) {
            GeneratorBuildPool holder = get(server, world, name);
            try {
                holder.adopt(generator);
            } catch (RuntimeException | Error failure) {
                if (holder.closed) {
                    try { close(world); }
                    catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
            return holder;
        }

        void abandon(W world) {
            GeneratorBuildPool holder;
            synchronized (this) {
                holder = active.get(world);
                if (holder == null) {
                    if (!wasClosed(world)) closed.add(new WeakReference<>(world));
                    return;
                }
            }
            try {
                holder.abandonUnadopted();
            } finally {
                if (holder.closed) {
                    synchronized (this) {
                        if (active.get(world) == holder) {
                            active.remove(world);
                            if (!wasClosed(world)) closed.add(new WeakReference<>(world));
                        }
                    }
                }
            }
        }

        void close(W world) {
            GeneratorBuildPool holder;
            synchronized (this) {
                if (!wasClosed(world)) closed.add(new WeakReference<>(world));
                holder = active.remove(world);
            }
            if (holder != null) holder.close();
        }

        synchronized void start(Object server) {
            if (server == null) throw new NullPointerException("server");
            if (this.server != null && this.server != server)
                throw new IllegalStateException("The previous server's build pools are still open");
            this.server = server;
        }

        void stop() {
            List<GeneratorBuildPool> holders;
            synchronized (this) {
                server = null;
                holders = new ArrayList<>(active.values());
                for (W world : active.keySet()) closed.add(new WeakReference<>(world));
                active.clear();
            }
            Throwable failure = null;
            for (GeneratorBuildPool holder : holders) {
                try { holder.close(); }
                catch (RuntimeException | Error cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof RuntimeException thrown) throw thrown;
            if (failure instanceof Error thrown) throw thrown;
        }

        private boolean wasClosed(W world) {
            boolean found = false;
            for (Iterator<WeakReference<W>> i = closed.iterator(); i.hasNext();) {
                W retired = i.next().get();
                if (retired == null) i.remove();
                else if (retired == world) found = true;
            }
            return found;
        }
    }
}
