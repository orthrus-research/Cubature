package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Samples the overworld's {@link ICubicTerrainPreview} over a square window at several footprints exactly as
 * Cubature's DH generator does: one column at each cell's integer centre, told the cell width, one sampler per
 * 64 x 64-cell tile. Footprints run in the given order (coarse first leaves coarse tiles cold, as far tiles are in
 * game). Per footprint it writes little-endian rasters of the top of the highest non-air run, the top of the highest
 * ground run (not leaves, logs, plants or snow layers), the top of the highest water run, the kind of the topmost run,
 * and each tile's thread CPU time. Diagnostic only: nothing is placed in the world.
 */
final class LabPreviewSurvey {
    static final int TILE = 64;
    static final byte NONE = 0, GROUND = 1, LEAVES = 2, LOG = 3, WATER = 4, PLANT = 5;
    private final MinecraftServer server;
    final String id;
    private final int minX, minZ, size, threads;
    private final int[] footprints;
    private final Path output;
    private volatile boolean cancelled;

    LabPreviewSurvey(MinecraftServer server, String id, int minX, int minZ, int size, String footprints, int threads) {
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Survey id must be a plain name");
        this.server = server; this.id = id; this.minX = minX; this.minZ = minZ; this.size = size;
        this.threads = Math.max(1, Math.min(threads, Runtime.getRuntime().availableProcessors()));
        this.footprints = Arrays.stream(footprints.split(",")).mapToInt(Integer::parseInt).toArray();
        for (int f : this.footprints)
            if (f < 1 || Integer.bitCount(f) != 1 || size % f != 0 || (size / f) % tileCells(size / f) != 0)
                throw new IllegalArgumentException("Each footprint must be a power of two whose tiles divide the window");
        output = server.getFile("cubiclab-surveys").toPath().resolve(id);
    }

    void start() throws IOException {
        WorldServer world = server.getWorld(0);
        Object generator = ((ICubicWorldServer) world).getCubeGenerator();
        if (!(generator instanceof ICubicTerrainPreview)) throw new IllegalStateException("The overworld generator has no terrain preview");
        Files.createDirectories(output);
        if (Files.exists(output.resolve("summary.json"))) throw new IllegalStateException("Survey " + id + " already exists");
        ICubicTerrainPreview provider = (ICubicTerrainPreview) generator;
        Thread runner = new Thread(() -> run(provider, world.getSeed()), "Cubic lab survey " + id);
        runner.setDaemon(true); runner.start();
    }

    void cancel() { cancelled = true; }

    /** DH's 64-cell tile, or one tile across the window when the footprint leaves fewer cells. */
    static int tileCells(int cells) { return Math.min(TILE, cells); }

    private void run(ICubicTerrainPreview provider, long seed) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema", 1); summary.put("id", id); summary.put("seed", seed);
        summary.put("minX", minX); summary.put("minZ", minZ); summary.put("size", size); summary.put("threads", threads);
        summary.put("scope", "Riven's preview sampler at each footprint, sampled as Cubature's DH generator does. Rasters are "
                + "row-major (z, then x), little-endian; i16 tops are exclusive Y, -32768 where absent. kind: 0 none, 1 ground, "
                + "2 leaves, 3 log, 4 water, 5 plant or snow layer. tile-cpu.u64 holds each tile's thread CPU nanoseconds.");
        List<Object> passes = new ArrayList<>();
        summary.put("footprints", passes);
        try {
            for (int f : footprints) {
                if (cancelled) throw new CancellationException("Survey cancelled");
                passes.add(pass(provider, f));
                message("CUBICLAB_SURVEY_PROGRESS id=" + id + " footprint=" + f + " done");
            }
            LabData.write(output.resolve("summary.json"), summary);
            message("CUBICLAB_SURVEY_RESULT id=" + id + " PASS " + output);
        } catch (Throwable failure) {
            message("CUBICLAB_SURVEY_RESULT id=" + id + " FAIL " + failure);
        }
    }

    private Map<String, Object> pass(ICubicTerrainPreview provider, int f) throws Exception {
        int cells = size / f, tile = tileCells(cells), tiles = cells / tile;
        short[] top = new short[cells * cells], ground = new short[cells * cells], water = new short[cells * cells];
        byte[] kind = new byte[cells * cells];
        AtomicLongArray cpu = new AtomicLongArray(tiles * tiles);
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "Cubic lab survey f" + f + " #" + counter.incrementAndGet());
            thread.setDaemon(true); return thread;
        });
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long started = System.nanoTime();
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int tz = 0; tz < tiles; tz++)
                for (int tx = 0; tx < tiles; tx++) {
                    int ox = tx, oz = tz;
                    work.add(pool.submit(() -> {
                        long cpuStart = bean.getCurrentThreadCpuTime();
                        ICubicTerrainPreview.Sampler sampler = provider.createTerrainPreviewSampler();
                        Column column = new Column();
                        for (int z = 0; z < tile; z++) {
                            if (cancelled) throw new CancellationException("Survey cancelled");
                            for (int x = 0; x < tile; x++) {
                                int cx = ox * tile + x, cz = oz * tile + z;
                                // The same integer centre CubicTerrainPreviewGenerator samples.
                                column.reset();
                                sampler.sampleColumn(minX + cx * f + f / 2, minZ + cz * f + f / 2, f, column);
                                int i = cz * cells + cx;
                                top[i] = column.top; ground[i] = column.ground; water[i] = column.water; kind[i] = column.kind;
                            }
                        }
                        cpu.set(oz * tiles + ox, bean.getCurrentThreadCpuTime() - cpuStart);
                        return null;
                    }));
                }
            for (Future<?> future : work) future.get();
        } finally {
            pool.shutdownNow();
        }
        long wall = System.nanoTime() - started;
        String prefix = "f" + f + "-";
        writeShorts(output.resolve(prefix + "top.i16"), top);
        writeShorts(output.resolve(prefix + "ground.i16"), ground);
        writeShorts(output.resolve(prefix + "water.i16"), water);
        Files.write(output.resolve(prefix + "kind.u8"), kind);
        ByteBuffer buffer = ByteBuffer.allocate(cpu.length() * 8).order(ByteOrder.LITTLE_ENDIAN);
        long cpuTotal = 0;
        for (int i = 0; i < cpu.length(); i++) { buffer.putLong(cpu.get(i)); cpuTotal += cpu.get(i); }
        Files.write(output.resolve(prefix + "tile-cpu.u64"), buffer.array());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("footprint", f); row.put("cells", cells); row.put("tileCells", tile); row.put("tiles", tiles * tiles);
        row.put("wallNanos", wall); row.put("cpuNanos", cpuTotal);
        row.put("cpuNanosPerColumn", (double) cpuTotal / ((long) cells * cells));
        return row;
    }

    private static void writeShorts(Path path, short[] values) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short value : values) buffer.putShort(value);
        Files.write(path, buffer.array());
    }

    private void message(String value) { server.sendMessage(new TextComponentString(value)); }

    /** Keeps only what the survey reports: the topmost runs of each kind, from the runs the sampler emits. */
    static final class Column implements ICubicTerrainPreview.ColumnConsumer {
        short top, ground, water; byte kind;
        void reset() { top = ground = water = Short.MIN_VALUE; kind = NONE; }
        @Override public void begin(Biome biome) {}
        @Override public void addRun(int bottom, int topExclusive, IBlockState state) {
            Material material = state.getMaterial();
            if (material == Material.AIR) return;
            byte k = kindOf(state, material);
            // Runs arrive bottom to top, so the last non-air run is the topmost.
            top = (short) topExclusive; kind = k;
            if (k == GROUND) ground = (short) topExclusive;
            if (k == WATER) water = (short) topExclusive;
        }
        static byte kindOf(IBlockState state, Material material) {
            if (material == Material.WATER) return WATER;
            if (material == Material.LEAVES) return LEAVES;
            if (material == Material.WOOD && state.getBlock() != Blocks.PLANKS) return LOG;
            if (material == Material.PLANTS || material == Material.VINE || material == Material.CACTUS
                    || material == Material.SNOW || material == Material.WEB) return PLANT;
            return GROUND;
        }
    }
}
