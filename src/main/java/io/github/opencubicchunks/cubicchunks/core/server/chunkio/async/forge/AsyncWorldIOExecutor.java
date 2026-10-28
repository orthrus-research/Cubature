/*
 * Minecraft Forge
 * Copyright (c) 2016.
 * Cubature changes by Orthrus Research, last implementation change 2026-10-18; see MODIFICATIONS.md.
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.ICubeIO;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.io.IOException;

/**
 * Brazenly copied from Forge and Sponge and reimplemented to suit our needs: Load cubes and columns outside the main
 * thread, then publish ready results on the server owner with a shared per-tick allowance.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
@Mod.EventBusSubscriber
public class AsyncWorldIOExecutor {

    private static final int BASE_THREADS = 1;
    private static final int PLAYERS_PER_THREAD = 50;
    private static final int COMPLETIONS_PER_TICK = 256;
    private static final long COMPLETION_NANOS_PER_TICK = TimeUnit.MILLISECONDS.toNanos(2);

    private static final Map<QueuedCube, AsyncCubeIOProvider> cubeTasks = new ConcurrentHashMap<>(20000, 0.8f, 1);
    private static final Map<QueuedColumn, AsyncColumnIOProvider> columnTasks = Maps.newConcurrentMap();

    private static ThreadPoolExecutor cubeThreadPool;
    private static AsyncCompletionQueue completions;
    private static final ConcurrentLinkedQueue<ReadyNotification<?,?>> retiredCompletions = new ConcurrentLinkedQueue<>();
    private static final WeakWorldClosures<World> closingWorlds = new WeakWorldClosures<>();

    // Separate parent-column reads from cube decode; only dependency-ready cubes enter their executor.
    private static ThreadPoolExecutor columnThreadPool;

    // this keeps track of which columns need to be kept loaded for which currently being loaded cubes
    // this allows to avoid a column being unloaded while a cube that uses it is being loaded, which would lead to hard to debug errors
    private static final Multimap<QueuedColumn, AsyncCubeIOProvider> loadingCubesColumnMap =
            Multimaps.newMultimap(new ConcurrentHashMap<>(), Sets::newConcurrentHashSet);

    static {
        initExecutors();
    }

    private static void initExecutors() {
        completions = new AsyncCompletionQueue(COMPLETIONS_PER_TICK, COMPLETION_NANOS_PER_TICK, System::nanoTime);
        final AtomicInteger threadCounter = new AtomicInteger();
        cubeThreadPool = new ThreadPoolExecutor(BASE_THREADS, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread thread = new Thread(r, "Cube I/O Thread #" + threadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
        );
        columnThreadPool = new ThreadPoolExecutor(BASE_THREADS, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread thread = new Thread(r, "Column I/O Thread #" + threadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    /**
     * Load a cube, directly.
     *
     * @param world The world in which the cube lies
     * @param loader The file loader for cubes
     * @param cache the cube cache used to load cubes and columns
     * @param cubeX X coordinate of the cube to load
     * @param cubeY Y coordinate of the cube to load
     * @param cubeZ Z coordinate of the cube to load
     *
     * @return The loaded cube, or null if either not present or the load failed
     */
    @Nullable
    public static Cube syncCubeLoad(World world, ICubeIO loader, CubeProviderServer cache, int cubeX, int cubeY, int cubeZ) {
        ensureWorldOpen(world);
        Chunk column = cache.getColumn(cubeX, cubeZ, ICubeProviderServer.Requirement.LIGHT);
        QueuedCube key = new QueuedCube(cubeX, cubeY, cubeZ, world);
        AsyncCubeIOProvider task = cubeTasks.get(key);
        if (task == null) {
            task = new AsyncCubeIOProvider(key, loader);
            QueuedColumn columnKey = new QueuedColumn(cubeX, cubeZ, world);
            AsyncCubeIOProvider pinnedTask = task;
            loadingCubesColumnMap.put(columnKey, pinnedTask);
            task.setCleanup(() -> loadingCubesColumnMap.remove(columnKey, pinnedTask));
            cubeTasks.put(key, task);
            prepareSynchronousExecution(world,cubeTasks,key,task,cubeThreadPool);
        }
        // Native load events can request this coordinate synchronously or asynchronously.
        // They must see the same restored object, not restore it a second time or replace its task.
        if (!task.isCompleting()) {
            task.setColumnForSynchronousLoad(column);
            runTask(task);
            ensureWorldOpen(world);
            AsyncTaskCompletion.completeForSynchronousLoad(cubeTasks, key, task);
        }
        return task.get();
    }

    /**
     * Load a column, directly
     *
     * @param world The world in which the column lies
     * @param loader The file loader for columns
     * @param x column x position
     * @param z column z position
     * @param setLoadingColumnCallback callback to run just before and after the load event is posted
     *
     * @return The loaded column
     */
    @Nullable public static Chunk syncColumnLoad(World world, ICubeIO loader, int x, int z, Consumer<Chunk> setLoadingColumnCallback) {
        ensureWorldOpen(world);
        QueuedColumn key = new QueuedColumn(x, z, world);
        AsyncColumnIOProvider task = columnTasks.get(key);
        if (task == null) {
            task = new AsyncColumnIOProvider(key, loader, ((ICubicWorldInternal.Server) world).getCubeCache().getCubeGenerator(),
                    setLoadingColumnCallback, error -> failDependentCubes(key, error));
            columnTasks.put(key, task);
            prepareSynchronousExecution(world,columnTasks,key,task,columnThreadPool);
        }
        if (!task.isCompleting()) {
            runTask(task);
            ensureWorldOpen(world);
            AsyncTaskCompletion.completeForSynchronousLoad(columnTasks, key, task);
        }
        return task.get();
    }

    /**
     * Runs the async part in current thread or blocks until already running async part is finished
     */
    private static void runTask(AsyncCubeIOProvider task) {
        runTask(cubeThreadPool, task);
    }

    /**
     * Runs the async part in current thread or blocks until already running async part is finished
     */
    private static void runTask(AsyncColumnIOProvider task) {
        runTask(columnThreadPool, task);
    }

    /**
     * Runs the async part in current thread or blocks until already running async part is finished.
     * <p>
     * Uses the given ThreadPoolExecutor.
     */
    private static void runTask(ThreadPoolExecutor executor, AsyncIOProvider<?> task) {
        task.runOrAwait(executor);
    }

    /** Preserve the caller's cancellation key when loading adds a postprocessing consumer. */
    public static <T> Consumer<T> withCallbackIdentity(Object identity, Consumer<T> callback) {
        return AsyncIOProvider.withCallbackIdentity(identity, callback);
    }

    //Queue the Cube to be loaded, and call the runnable when isFinished
    // Sponge: Runnable -> Consumer<Cube>

    /**
     * Queue a cube load. Its parent is published before cube decode is admitted; callbacks run on the server owner.
     *
     * @param world The world of the cube
     * @param loader The file loader for this world
     * @param cache The server cube cache
     * @param x cube x position
     * @param y cube y position
     * @param z cube z position
     * @param runnable The callback
     */
    public static void queueCubeLoad(World world, ICubeIO loader, CubeProviderServer cache, int x, int y, int z, Consumer<Cube> runnable) {
        ensureWorldOpen(world);
        QueuedCube key = new QueuedCube(x, y, z, world);
        QueuedColumn columnKey = new QueuedColumn(x, z, world);
        AsyncCubeIOProvider task = cubeTasks.get(key);
        boolean created = task == null;

        if (created) {
            task = new AsyncCubeIOProvider(key, loader);
            task.addCallback(runnable); // Add before calling execute for thread safety
            AsyncCubeIOProvider pinnedTask = task;
            loadingCubesColumnMap.put(columnKey, pinnedTask);
            task.setCleanup(() -> loadingCubesColumnMap.remove(columnKey, pinnedTask));
            cubeTasks.put(key, task);
            prepareExecution(world, cubeTasks, key, task, cubeThreadPool);
        } else {
            task.addCallback(runnable);
        }

        if (task.isFinished()) return;
        Chunk loadedIColumn = cache.getLoadedColumn(x, z);
        if (loadedIColumn != null) {
            task.setColumn(loadedIColumn);
        } else if (created) {
            // An unresolved parent never occupies the cube executor's only worker.
            cache.asyncGetColumn(x, z, ICubeProviderServer.Requirement.LOAD, task::setColumn);
        }

    }

    /**
     * Queue a column load, running the specified callback when the load has finished
     *
     * @param world The world of the column
     * @param loader The file loader for this world
     * @param x column x position
     * @param z column z position
     * @param runnable The callback
     * @param setLoadingColumnCallback callback to run just before and after the load event is posted
     */
    public static void queueColumnLoad(World world, ICubeIO loader, int x, int z, Consumer<Chunk> runnable, Consumer<Chunk> setLoadingColumnCallback) {
        ensureWorldOpen(world);
        QueuedColumn key = new QueuedColumn(x, z, world);
        AsyncColumnIOProvider task = columnTasks.get(key);
        if (task == null) {
            task = new AsyncColumnIOProvider(key, loader, ((ICubicWorldInternal.Server) world).getCubeCache().getCubeGenerator(),
                    setLoadingColumnCallback, error -> failDependentCubes(key, error));
            task.addCallback(runnable); // Add before calling execute for thread safety
            columnTasks.put(key, task);
            prepareExecution(world, columnTasks, key, task, columnThreadPool);
            task.enqueue();
        } else {
            task.addCallback(runnable);
        }
    }

    private static <K, T extends AsyncIOProvider<?>> void prepareExecution(World world, Map<K, T> tasks, K key, T task, ThreadPoolExecutor executor) {
        AsyncCompletionQueue queue = completions;
        ReadyNotification<K,T> completion = new ReadyNotification<>(world,tasks,key,task);
        task.prepareExecution(executor, () -> {
            // An old worker finishing after shutdown must not publish into a new world's queue.
            if (closingWorlds.isClosed(world) || !queue.offer(completion)) completion.retire();
        },()->completion.discardAfterStop(queue));
    }

    private static <K,T extends AsyncIOProvider<?>> void prepareSynchronousExecution(World world, Map<K,T> tasks,
            K key, T task, ThreadPoolExecutor executor) {
        AsyncCompletionQueue queue = completions;
        ReadyNotification<K,T> completion = new ReadyNotification<>(world, tasks, key, task);
        task.prepareExecution(executor, null, () -> completion.discardAfterStop(queue));
    }

    private static void ensureWorldOpen(World world) {
        if (closingWorlds.isClosed(world)) throw new IllegalStateException("Cube/column storage is closing for this world");
    }

    /** Close only this world's readers. Failure leaves its backend open and running ownership retained. */
    public static void shutdownWorldBlocking(World world) throws IOException {
        List<AsyncTaskQuiescence.Registration> owned = new ArrayList<>();
        AsyncTaskQuiescence.select(owned, cubeTasks, key -> key.world == world, cubeThreadPool);
        AsyncTaskQuiescence.select(owned, columnTasks, key -> key.world == world, columnThreadPool);
        // A load event may request close on this same owner before provider callbacks finish.
        // Reject without poisoning admissions, cancelling consumers or releasing their pins.
        AsyncTaskQuiescence.checkNotPublishing(owned);
        closingWorlds.close(world);
        try {
            AsyncTaskQuiescence.cancelAndAwait(owned, TimeUnit.SECONDS.toNanos(10));
        } finally {
            // Include synchronous readers with no ready callback, and keep timed-out readers
            // discoverable until an owner tick observes their actual stop. Repeated closes coalesce.
            for (Map.Entry<QueuedCube,AsyncCubeIOProvider> entry : cubeTasks.entrySet()) {
                if (entry.getKey().world == world) new ReadyNotification<>(world, cubeTasks, entry.getKey(), entry.getValue()).retire();
            }
            for (Map.Entry<QueuedColumn,AsyncColumnIOProvider> entry : columnTasks.entrySet()) {
                if (entry.getKey().world == world) new ReadyNotification<>(world, columnTasks, entry.getKey(), entry.getValue()).retire();
            }
            completions.discard(value -> value instanceof ReadyNotification && ((ReadyNotification<?,?>) value).discardFor(world));
            retiredCompletions.removeIf(value -> value.discardFor(world));
        }
    }

    private static final class ReadyNotification<K,T extends AsyncIOProvider<?>> implements Runnable {
        final World world;
        final Map<K,T> tasks;
        final K key;
        final T task;
        ReadyNotification(World world, Map<K,T> tasks, K key, T task) {
            this.world = world;
            this.tasks = tasks;
            this.key = key;
            this.task = task;
        }
        @Override public void run() {
            if (closingWorlds.isClosed(world)) {
                if (!discardStopped()) retire();
            } else AsyncTaskCompletion.completeIfCurrent(tasks, key, task);
        }
        boolean discardFor(World target) { return world == target && discardStopped(); }
        boolean discardStopped() {
            if (!task.isExecutionStopped()) return false;
            tasks.remove(key, task);
            task.cleanup();
            return true;
        }
        void retire() {
            AsyncTaskRetirement.enqueue(task, retiredCompletions, this, this::discardStopped);
        }
        void discardAfterStop(AsyncCompletionQueue queue) {
            if (closingWorlds.isClosed(world) && discardStopped()) {
                queue.discard(value -> value instanceof ReadyNotification && ((ReadyNotification<?,?>) value).task == task);
                retiredCompletions.removeIf(value -> value.task == task);
            }
        }
    }

    private static void failDependentCubes(QueuedColumn key, Throwable error) {
        for (AsyncCubeIOProvider task : loadingCubesColumnMap.get(key)) task.failDependency(error);
    }

    /**
     * Notify the loader that this cube isn't needed anymore
     *
     * @param world The world
     * @param x cube x position
     * @param y cube y position
     * @param z cube z position
     * @param runnable The runnable that should be dropped
     */
    public static void dropQueuedCubeLoad(World world, int x, int y, int z, Consumer<Cube> runnable) {
        QueuedCube key = new QueuedCube(x, y, z, world);
        AsyncCubeIOProvider task = cubeTasks.get(key);
        if (task == null) {
            CubicChunks.LOGGER.warn("Attempting to drop cube that wasn't queued in {} @ ({}, {}, {})", world, x, y, z);
            return;
        }

        task.removeCallback(runnable);

        AsyncTaskCompletion.cancelIfUnneeded(cubeTasks, key, task, cubeThreadPool);
    }

    /**
     * Notify the loader that this column isn't needed anymore
     *
     * @param world The world
     * @param x column x position
     * @param z column z postion
     * @param runnable The runnable that should be dropped
     */
    public static void dropQueuedColumnLoad(World world, int x, int z, Consumer<Chunk> runnable) {
        dropQueuedColumnLoad(world, x, z, (Object) runnable);
    }

    /** Cancel the vanilla asynchronous loadChunk request using its original Runnable. */
    public static void dropQueuedColumnLoad(World world, int x, int z, Runnable runnable) {
        dropQueuedColumnLoad(world, x, z, (Object) runnable);
    }

    private static void dropQueuedColumnLoad(World world, int x, int z, Object runnable) {
        QueuedColumn key = new QueuedColumn(x, z, world);
        AsyncColumnIOProvider task = columnTasks.get(key);
        if (task == null) {
            CubicChunks.LOGGER.warn("Attempting to drop column that wasn't queued in {} @ ({}, {})", world, x, z);
            return;
        }

        task.removeCallback(runnable);

        if (!task.hasCallbacks()) {
            AsyncTaskCompletion.cancelIfUnneeded(columnTasks, key, task, columnThreadPool);
        }

        // Each cube owns its dependency subscription; other cube consumers keep their parent alive.
    }

    /** All-world process/server stop only. Per-world storage disposal uses shutdownWorldBlocking. */
    public static void shutdownNowBlocking() {
        // best effort wait for up to 10 seconds each
        // Cancel dependency-waiting/queued work, then stop cubes before disposing their parent storage.
        List<AsyncCubeIOProvider> stoppingCubeTasks = new ArrayList<>(cubeTasks.values());
        List<Runnable> neverStartedCubes = new ArrayList<>();
        completions.close();
        for (AsyncCubeIOProvider task : stoppingCubeTasks) {
            if (task.cancelBeforeStart(cubeThreadPool)) neverStartedCubes.add(task);
        }
        for (AsyncColumnIOProvider task : columnTasks.values()) task.cancelBeforeStart(columnThreadPool);
        cubeThreadPool.shutdownNow();
        cubeTasks.clear();
        boolean interrupted = false;
        try {
            cubeThreadPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            interrupted = true;
        }
        columnThreadPool.shutdownNow();
        columnTasks.clear();
        try {
            columnThreadPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            interrupted = true;
        }
        AsyncTaskCompletion.cleanupStoppedTasks(stoppingCubeTasks, neverStartedCubes);
        // A reader outliving this all-world best-effort stop keeps its column pin until actual stop.
        // initialize for next use
        initExecutors();
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Run a synchronous tick, finishing the loading process for load tasks that are ready
     */
    public static void tick() {
        ReadyNotification<?,?> retired;
        for (int i = 0; i < COMPLETIONS_PER_TICK && (retired = retiredCompletions.poll()) != null; i++) {
            if (!retired.discardStopped()) {
                retiredCompletions.add(retired);
                // Stop cleanup can race this requeue just as it can the first publication.
                if (retired.discardStopped()) retiredCompletions.remove(retired);
            }
        }
        completions.drain();
    }

    /**
     * Resize async loading pool thread count when players join or leave
     *
     * @param players New player count
     */
    private static void adjustPoolSize(int players) {
        cubeThreadPool.setCorePoolSize(Math.max(BASE_THREADS, players / PLAYERS_PER_THREAD));
    }

    public static boolean canDropColumn(World world, int x, int z) {
        return !loadingCubesColumnMap.containsKey(new QueuedColumn(x, z, world));
    }

    // Resize thread pool based on player count
    @SubscribeEvent
    public static void onPlayerLoggedIn(@Nonnull PlayerEvent.PlayerLoggedInEvent evt) {
        MinecraftServer server = evt.player.getServer();
        if (server != null) {
            adjustPoolSize(server.getCurrentPlayerCount());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(@Nonnull PlayerEvent.PlayerLoggedOutEvent evt) {
        MinecraftServer server = evt.player.getServer();
        if (server != null) {
            adjustPoolSize(server.getCurrentPlayerCount());
        }
    }

    // Sync completion of loading
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent evt) {
        if (evt.phase == TickEvent.Phase.START) {
            completions.beginTick();
            tick();
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent evt) {
        if (evt.phase == TickEvent.Phase.END) {
            tick();
        }
    }
}
