package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import cubicchunks.regionlib.impl.EntryLocation3D;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.UnpooledByteBufAllocator;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Detached real cube NBT, unchanged RegionLib format and force ordering, new directories only. */
public final class LabStorageStrategies implements AutoCloseable {
    private final Map<CubePos, NBTTagCompound> snapshots = new LinkedHashMap<>();
    private final List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CubicLab storage strategies"); thread.setDaemon(true); return thread;
    });
    private final Path root;
    private final long snapshotNanos;
    private Future<?> task;
    private boolean complete;

    /** Owner thread only: IONbtWriter touches live cubes and posts normal serialization events. */
    public LabStorageStrategies(Path root, List<Cube> sources) throws IOException {
        if (!CubicChunksConfig.useShadowPagingIO) throw new IllegalStateException("Storage experiment requires shadow paging");
        Files.createDirectory(root);
        this.root = root;
        long begin = System.nanoTime();
        for (Cube cube : sources) snapshots.put(cube.getCoords(), IONbtWriter.write(cube).copy());
        snapshotNanos = System.nanoTime() - begin;
        if (snapshots.size() != 1024) throw new IllegalArgumentException("Expected all 1024 fixture cubes");
    }

    public boolean observe() throws IOException {
        if (task == null) task = worker.submit(() -> {
            try { experiment(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        if (!task.isDone()) return false;
        try { task.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted observing storage experiment", e); }
        catch (ExecutionException e) { throw new IOException("Storage experiment failed", e.getCause()); }
        complete = true; return true;
    }

    private void experiment() throws IOException {
        List<Variant> variants = Arrays.asList(new Variant(1, false, 0), new Variant(16, false, 0),
                new Variant(64, false, 0), new Variant(256, false, 0), new Variant(64, true, 0),
                new Variant(64, true, 512), new Variant(64, true, 8192));
        // Exercise JIT/codec paths before measured trials; no warmup files share trial directories.
        for (int gzipBuffer : new int[]{0, 512, 8192}) {
            try (MeteredStorage warmup = new MeteredStorage(root.resolve("warmup-" + gzipBuffer), gzipBuffer)) {
                Iterator<NBTTagCompound> nbt = snapshots.values().iterator();
                for (int i = 0; i < 256; i++) {
                    if (!nbt.hasNext()) nbt = snapshots.values().iterator();
                    ByteBuf buffer = warmup.compressNBT(nbt.next()); buffer.release();
                }
            }
        }
        for (int repetition = 0; repetition < 3; repetition++) {
            List<Variant> order = new ArrayList<>(variants); Collections.shuffle(order, new Random(91025L + repetition));
            for (Variant variant : order) {
                PendingCubeWrites.checkInterrupted();
                rows.add(trial(variant, repetition));
            }
        }
    }

    private Map<String, Object> trial(Variant variant, int repetition) throws IOException {
        Path directory = root.resolve("r" + repetition + "-" + variant.name());
        List<CubePos> positions = new ArrayList<>(snapshots.keySet());
        if (variant.regionOrder) positions.sort(Comparator.comparing(LabStorageStrategies::region)
                .thenComparingInt(CubePos::getX).thenComparingInt(CubePos::getZ).thenComparingInt(CubePos::getY));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("variant", variant.name()); row.put("repetition", repetition); row.put("batchSize", variant.batch);
        row.put("order", variant.regionOrder ? "region" : "column");
        row.put("compression", variant.gzipBuffer == 0 ? "production-direct" : "heap-gzip-" + variant.gzipBuffer);
        row.put("cubes", positions.size()); row.put("directory", directory.toString());
        long writeNanos = 0, flushNanos, compressedBytes, compressionNanos; int batches = 0, regionGroups = 0;
        try (MeteredStorage storage = new MeteredStorage(directory, variant.gzipBuffer)) {
            row.put("writeStartUtc", Instant.now().toString());
            for (int from = 0; from < positions.size(); from += variant.batch) {
                PendingCubeWrites.checkInterrupted();
                Map<CubePos, NBTTagCompound> batch = new LinkedHashMap<>(); Set<String> regions = new HashSet<>();
                for (CubePos pos : positions.subList(from, Math.min(from + variant.batch, positions.size()))) {
                    batch.put(pos, snapshots.get(pos)); regions.add(region(pos));
                }
                regionGroups += regions.size(); batches++;
                long begin = System.nanoTime();
                storage.writeBatch(new ICubicStorage.NBTBatch(Collections.emptyMap(), batch));
                writeNanos += System.nanoTime() - begin;
            }
            long begin = System.nanoTime(); storage.flush(); flushNanos = System.nanoTime() - begin;
            row.put("writeEndUtc", Instant.now().toString());
            compressedBytes = storage.bytes; compressionNanos = storage.compressionNanos;
        }
        row.put("batches", batches); row.put("calculatedRegionCommitGroups", regionGroups);
        row.put("writeNanos", writeNanos); row.put("compressionNanos", compressionNanos);
        row.put("writeOutsideCompressionNanos", writeNanos - compressionNanos);
        row.put("flushNanos", flushNanos); row.put("compressedBytes", compressedBytes);
        List<CubePos> local = new ArrayList<>(snapshots.keySet()); local.sort(Comparator.comparing(LabStorageStrategies::region));
        List<CubePos> shuffled = new ArrayList<>(local); Collections.shuffle(shuffled, new Random(7525));
        // Alternate which read order gets the first reopen; neither order is OS-cache-cold.
        List<String> readOrder = repetition % 2 == 0 ? Arrays.asList("region", "shuffled") : Arrays.asList("shuffled", "region");
        for (String order : readOrder) {
            long readNanos = 0; int verified = 0;
            try (MeteredStorage storage = new MeteredStorage(directory, 0)) {
                for (CubePos pos : order.equals("region") ? local : shuffled) {
                    PendingCubeWrites.checkInterrupted(); long begin = System.nanoTime();
                    NBTTagCompound actual = storage.readCube(pos); readNanos += System.nanoTime() - begin;
                    if (!snapshots.get(pos).equals(actual)) throw new IOException("NBT round trip differs at " + pos);
                    verified++;
                }
            }
            row.put(order + "ReadNanos", readNanos); row.put(order + "VerifiedCubes", verified);
        }
        row.put("firstReadOrder", readOrder.get(0)); row.put("passed", true);
        io.github.opencubicchunks.cubicchunks.core.CubicChunks.LOGGER.info("CUBICLAB_STORAGE_TRIAL repetition={} variant={} writeMs={} compressionMs={}",
                repetition, variant.name(), writeNanos / 1_000_000.0, compressionNanos / 1_000_000.0);
        return row;
    }

    private static String region(CubePos pos) {
        return new EntryLocation3D(pos.getX(), pos.getY(), pos.getZ()).getRegionKey().getName();
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", complete); result.put("fixtureCubes", snapshots.size()); result.put("snapshotNanos", snapshotNanos);
        synchronized (rows) { result.put("trials", new ArrayList<>(rows)); }
        result.put("repetitions", 3);
        result.put("boundary", "Detached real cube NBT, dedicated worker, fresh trial files, unchanged format and force ordering. "
                + "Read timings include decompression/NBT parsing; equality checks are outside timers. Reopen is not OS cache eviction. "
                + "Write time outside compression includes maps, allocation, locks and backend I/O; it is not exclusive disk time. "
                + "Region groups are calculated from submitted keys, not syscall counts. RegionLib flush/close touches its shared global region cache. "
                + "No writer-queue admission, production default change, power-loss or full-world atomicity qualification.");
        return result;
    }

    @Override public void close() throws IOException {
        if (task != null && !task.isDone()) task.cancel(true);
        worker.shutdown();
        try { if (!worker.awaitTermination(10, TimeUnit.SECONDS)) throw new IOException("Storage experiment worker still owns its trial files"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted closing storage experiment", e); }
    }

    private static final class Variant {
        final int batch, gzipBuffer; final boolean regionOrder;
        Variant(int batch, boolean regionOrder, int gzipBuffer) { this.batch = batch; this.regionOrder = regionOrder; this.gzipBuffer = gzipBuffer; }
        String name() { return "batch" + batch + "-" + (regionOrder ? "region" : "column") + "-" + (gzipBuffer == 0 ? "direct" : "heap" + gzipBuffer); }
    }

    private static final class MeteredStorage extends RegionCubeStorage implements AutoCloseable {
        final int gzipBuffer; long compressionNanos, bytes;
        MeteredStorage(Path path, int gzipBuffer) throws IOException { super(path); this.gzipBuffer = gzipBuffer; }
        @Override protected ByteBuf compressNBT(NBTTagCompound nbt) throws IOException {
            long begin = System.nanoTime(); ByteBuf buffer;
            if (gzipBuffer == 0) buffer = super.compressNBT(nbt);
            else {
                buffer = UnpooledByteBufAllocator.DEFAULT.heapBuffer(); boolean success = false;
                try {
                    try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                            new GZIPOutputStream(new ByteBufOutputStream(buffer), gzipBuffer)))) {
                        CompressedStreamTools.write(nbt, output);
                    }
                    success = true;
                } finally { if (!success) buffer.release(); }
            }
            compressionNanos += System.nanoTime() - begin; bytes += buffer.readableBytes(); return buffer;
        }
    }
}
