package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService executor;
    private final Consumer<IOException> reportFailure;
    private final int columnLimit;
    private final int cubeLimit;
    private volatile IOException failure;

    PendingCubeWrites(ICubicStorage storage, int columnLimit, int cubeLimit, Consumer<IOException> reportFailure) {
        if (columnLimit < 1 || cubeLimit < 1) throw new IllegalArgumentException("Batch limits must be positive");
        this.storage = storage;
        this.columnLimit = columnLimit;
        this.cubeLimit = cubeLimit;
        this.reportFailure = reportFailure;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CubicChunks storage writer");
            thread.setDaemon(true);
            return thread;
        });
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

    void signal() {
        if (failure != null || !hasPending() || !scheduled.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                while (writeNext()) { /* bounded batches, one owner */ }
            } finally {
                scheduled.set(false);
                // A publication during worker exit must not be stranded.
                signal();
            }
        });
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

    /** Caller must exclude publishers. One failed retry returns an error, never a retry loop. */
    void flush() throws IOException {
        lockInterruptibly();
        try {
            failure = null;
            while (hasPending()) {
                checkInterrupted();
                writeBatch();
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

    void stop() { executor.shutdown(); }

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

    private void writeBatch() throws IOException {
        Map<ChunkPos, NBTTagCompound> columnBatch;
        Map<CubePos, NBTTagCompound> cubeBatch;
        synchronized (this) {
            columnBatch = columnOrder.take(columns, columnLimit);
            cubeBatch = cubeOrder.take(cubes, cubeLimit);
        }
        boolean success = false;
        try {
            storage.writeBatch(new ICubicStorage.NBTBatch(Collections.unmodifiableMap(columnBatch), Collections.unmodifiableMap(cubeBatch)));
            success = true;
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
