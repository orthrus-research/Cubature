package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/** Per-storage writer. Failed snapshots remain readable until an explicit flush retries them. */
final class PendingCubeWrites {
    final Map<ChunkPos, NBTTagCompound> columns = new ConcurrentHashMap<>();
    final Map<CubePos, NBTTagCompound> cubes = new ConcurrentHashMap<>();
    private final RegionOrder<ChunkPos> columnOrder = new RegionOrder<>(
            pos -> new EntryLocation2D(pos.x, pos.z).getRegionKey());
    private final RegionOrder<CubePos> cubeOrder = new RegionOrder<>(
            pos -> new EntryLocation3D(pos.getX(), pos.getY(), pos.getZ()).getRegionKey());
    // Fair: a flush or close waiting for the writer gets it after the current batch, instead of the
    // background writer re-taking it until the whole backlog is drained.
    private final ReentrantLock writer = new ReentrantLock(true);
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ICubicStorage storage;
    private final Scheduler scheduler;
    private final Consumer<IOException> reportFailure;
    private final int columnLimit;
    private final int cubeLimit;
    private final long gatherMillis;
    private volatile IOException failure;
    // Entries written so far, read without the lock by a drain round to know when its share is done. The
    // non-atomic += is safe: only writeBatch updates it, and always under the writer lock.
    private volatile long written;
    // Set by flush for its duration, under the writer lock, so its batches take whole regions.
    private boolean wholeRegions;

    /** Runs drain rounds after the gather window. Tests drive it by hand. */
    interface Scheduler {
        /** @throws RejectedExecutionException once shut down */
        void schedule(Runnable task, long delayMillis);

        /** Accepts no new rounds; rounds already scheduled still run. */
        void shutdown();
    }

    PendingCubeWrites(ICubicStorage storage, int columnLimit, int cubeLimit, Consumer<IOException> reportFailure) {
        this(storage, columnLimit, cubeLimit, 0, reportFailure);
    }

    /**
     * @param gatherMillis how long the background writer waits after the first publication before a drain round, so
     *     a region's snapshots share batches and each batch's durability forces cover more entries. A round writes
     *     what was pending when it began; later publications wait for the next window, so a steady stream of saves
     *     cannot keep the writer in small batches. An explicit flush, a close and {@link #writeNext} never wait.
     */
    PendingCubeWrites(ICubicStorage storage, int columnLimit, int cubeLimit, long gatherMillis,
            Consumer<IOException> reportFailure) {
        this(storage, columnLimit, cubeLimit, gatherMillis, writerThread(), reportFailure);
    }

    PendingCubeWrites(ICubicStorage storage, int columnLimit, int cubeLimit, long gatherMillis, Scheduler scheduler,
            Consumer<IOException> reportFailure) {
        if (columnLimit < 1 || cubeLimit < 1) throw new IllegalArgumentException("Batch limits must be positive");
        if (gatherMillis < 0) throw new IllegalArgumentException("Gather window must not be negative");
        this.storage = storage;
        this.columnLimit = columnLimit;
        this.cubeLimit = cubeLimit;
        this.gatherMillis = gatherMillis;
        this.scheduler = scheduler;
        this.reportFailure = reportFailure;
    }

    private static Scheduler writerThread() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "CubicChunks storage writer");
            thread.setDaemon(true);
            return thread;
        });
        return new Scheduler() {
            @Override public void schedule(Runnable task, long delayMillis) {
                executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            }

            @Override public void shutdown() { executor.shutdown(); }
        };
    }

    synchronized void putColumn(ChunkPos pos, NBTTagCompound nbt) {
        if (columns.put(pos, nbt) == null) columnOrder.addLast(pos);
    }

    synchronized void putCube(CubePos pos, NBTTagCompound nbt) {
        if (cubes.put(pos, nbt) == null) cubeOrder.addLast(pos);
    }

    NBTTagCompound readColumn(ChunkPos pos) {
        NBTTagCompound nbt = columns.get(pos);
        return nbt == null ? null : nbt.copy();
    }

    NBTTagCompound readCube(CubePos pos) {
        NBTTagCompound nbt = cubes.get(pos);
        return nbt == null ? null : nbt.copy();
    }

    boolean hasPending() { return !columns.isEmpty() || !cubes.isEmpty(); }
    boolean hasFailure() { return failure != null; }
    IOException failure() { return failure; }

    void signal() {
        if (failure != null || !hasPending() || !scheduled.compareAndSet(false, true)) return;
        // One round per gather window: publications before it ends join the round; those during it start the next.
        try {
            scheduler.schedule(this::runRound, gatherMillis);
        } catch (RejectedExecutionException stopped) {
            // Stopped by close, which drained the queue first; a round ending afterwards has nothing to hand on.
            scheduled.set(false);
        }
    }

    private void runRound() {
        try {
            drainRound();
        } catch (Throwable unexpected) {
            // writeNext reports batch I/O failures. Anything else (an Error from the backend, a throwing report)
            // would otherwise vanish into the executor's future. Hold the work for an explicit flush, as for I/O.
            // A failure already held means the report of that I/O failure threw: log the I/O failure, not just the
            // report's own error.
            IOException held;
            writer.lock();
            try {
                if (failure == null) failure = new IOException("Cube storage writer round failed", unexpected);
                else if (failure != unexpected) failure.addSuppressed(unexpected);
                held = failure;
            } finally {
                writer.unlock();
            }
            CubicChunks.LOGGER.error("Cube storage writer round failed; snapshots retained. Explicit flush will retry.",
                    held);
        } finally {
            scheduled.set(false);
            // A publication during worker exit must not be stranded.
            signal();
        }
    }

    /**
     * One round of the background drain: the entries pending as it begins, in bounded batches, and no more. Writes
     * from other owners meanwhile ({@link #writeNext} from a tick) count toward the round's share.
     */
    void drainRound() {
        long share = columns.size() + cubes.size(), start = written;
        while (written - start < share && writeNext()) { /* bounded batches, one owner */ }
    }

    boolean writeNext() {
        writer.lock();
        try {
            if (failure != null || !hasPending()) return false;
            try {
                writeBatch();
            } catch (IOException e) {
                failure = e;
                reportFailure.accept(e);
                return false;
            }
            return hasPending();
        } finally {
            writer.unlock();
        }
    }

    /**
     * Caller must exclude publishers. One failed retry returns an error, never a retry loop. A flush writes each
     * region's whole backlog as one batch, so a region file takes one data force and one header force per flush
     * instead of two per bounded batch; the backend's data-before-header order is unchanged.
     */
    void flush() throws IOException {
        lockInterruptibly();
        try {
            failure = null;
            wholeRegions = true;
            try {
                while (hasPending()) {
                    checkInterrupted();
                    writeBatch();
                }
            } finally {
                wholeRegions = false;
            }
            checkInterrupted();
            storage.flush();
        } catch (IOException e) {
            failure = e;
            throw e;
        } finally {
            writer.unlock();
        }
    }

    /**
     * Rounds already scheduled still run, but cannot reach a released backend. The facade passes this to its
     * {@link StorageCloseState}, which runs it until it succeeds once, after the backend release and even if that
     * fails. The facade enters that close only under its write lock and after a successful {@link #flush} emptied the
     * queue, and admits no publisher afterwards, so a late round's {@link #writeNext} finds nothing pending under the
     * writer lock and returns before writing. A flush that throws never reaches the release; a round scheduled before
     * it then finds the held failure and returns before writing as well.
     */
    void stop() { scheduler.shutdown(); }

    private void lockInterruptibly() throws InterruptedIOException {
        try {
            writer.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException error = new InterruptedIOException("Interrupted waiting for cube storage writer");
            error.initCause(e);
            throw error;
        }
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Interrupted draining cube storage writes");
    }

    /** One region's pending entries of each kind: all of them during a flush, else at most the batch limits. */
    private void writeBatch() throws IOException {
        Map<ChunkPos, NBTTagCompound> columnBatch;
        Map<CubePos, NBTTagCompound> cubeBatch;
        synchronized (this) {
            columnBatch = columnOrder.take(columns, wholeRegions ? Integer.MAX_VALUE : columnLimit);
            cubeBatch = cubeOrder.take(cubes, wholeRegions ? Integer.MAX_VALUE : cubeLimit);
        }
        boolean success = false;
        try {
            storage.writeBatch(new ICubicStorage.NBTBatch(Collections.unmodifiableMap(columnBatch), Collections.unmodifiableMap(cubeBatch)));
            success = true;
            written += columnBatch.size() + cubeBatch.size();
        } catch (RuntimeException e) {
            throw new IOException("Cube storage batch failed", e);
        } finally {
            synchronized (this) {
                finish(columns, columnOrder, columnBatch, success);
                finish(cubes, cubeOrder, cubeBatch, success);
            }
        }
    }

    /** Amortize region durability forces within each bounded batch; rotate regions between batches. */
    private static final class RegionOrder<K> {
        private final Function<K, Object> region;
        private final LinkedHashMap<Object, ArrayDeque<K>> groups = new LinkedHashMap<>();

        RegionOrder(Function<K, Object> region) { this.region = region; }

        void addLast(K key) {
            groups.computeIfAbsent(region.apply(key), ignored -> new ArrayDeque<>()).addLast(key);
        }

        Map<K, NBTTagCompound> take(Map<K, NBTTagCompound> pending, int limit) {
            Map<K, NBTTagCompound> batch = new LinkedHashMap<>();
            Iterator<Map.Entry<Object, ArrayDeque<K>>> iterator = groups.entrySet().iterator();
            if (!iterator.hasNext()) return batch;
            Map.Entry<Object, ArrayDeque<K>> group = iterator.next();
            Object groupKey = group.getKey();
            ArrayDeque<K> order = group.getValue();
            iterator.remove();
            while (batch.size() < limit && !order.isEmpty()) {
                K key = order.removeFirst();
                NBTTagCompound nbt = pending.get(key);
                if (nbt != null) batch.put(key, nbt);
            }
            if (!order.isEmpty()) groups.put(groupKey, order);
            return batch;
        }
    }

    private static <K> void finish(Map<K, NBTTagCompound> pending, RegionOrder<K> order,
            Map<K, NBTTagCompound> batch, boolean success) {
        batch.forEach((key, written) -> {
            // NBT value equality is not a save revision. Equal replacement snapshots still belong to the next write.
            if (success) pending.computeIfPresent(key, (ignored, current) -> current == written ? null : current);
            if (pending.containsKey(key)) order.addLast(key);
        });
    }
}
