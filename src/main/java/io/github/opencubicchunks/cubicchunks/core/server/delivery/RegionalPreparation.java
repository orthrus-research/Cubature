package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.border.WorldBorder;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Off-thread preparation of a generator's regional plans ahead of the players, within bounds (Riven
 * docs/DECISIONS.md, 2026-10-30, "Preparation within bounds"). Once a second it ranks the regions within
 * {@code regionalPreparationReachBlocks} of any player by time to reach: the distance to the region over the
 * player's speed toward it plus a walking baseline, so a standing player's regions go nearest first and a moving
 * player's regions ahead go before those behind. Regions within the native view run up to the build pool's size:
 * generation would build them on demand anyway, so preparing them only starts that work earlier. Regions beyond run
 * under the {@link PreparationGovernor}. When the generator declines a region for lack of room, regions beyond the
 * native view wait 30 seconds. A prepared region is not prepared again. The server thread owns all of this; the pool's
 * tasks only report back, through a queue.
 */
public final class RegionalPreparation {
    /** Blocks per second added to a player's speed toward a region, so standing players rank by distance. */
    static final double BASELINE_SPEED = 8;
    static final long DECLINED_PAUSE = TimeUnit.SECONDS.toNanos(30);
    static final long PLAN_PERIOD = TimeUnit.SECONDS.toNanos(1);
    static final long TRICKLE_PERIOD = TimeUnit.SECONDS.toNanos(5);

    /** A player's position, keyed by something stable from tick to tick. */
    record Position(Object id, double x, double z) {}

    /** Whether the world lets a region (block bounds) generate at all. */
    interface Bounds {
        boolean admits(double minX, double minZ, double maxX, double maxZ);
    }

    private final ICubeGenerator generator;
    @Nullable private final Executor pool;
    private final int parallelism, regionBlocks;
    private final PreparationGovernor governor;
    private final Set<Long> done = new HashSet<>();
    private final Map<Long, Boolean> running = new HashMap<>();
    private final ConcurrentLinkedQueue<long[]> finished = new ConcurrentLinkedQueue<>();
    private final Map<Object, double[]> lastSeen = new HashMap<>();
    private long[] order = new long[0];
    private boolean[] near = new boolean[0];
    private long nextPlan = Long.MIN_VALUE, declinedUntil = Long.MIN_VALUE;
    private boolean closed;
    private volatile boolean reported;
    // Server thread CPU time and tick count when the current one-second window began.
    private long windowCpu = -1;
    private int windowTick;

    RegionalPreparation(ICubeGenerator generator, @Nullable Executor pool, int parallelism) {
        this.generator = generator;
        this.regionBlocks = pool == null ? 0 : Math.max(0, generator.preparationRegionBlocks());
        this.pool = regionBlocks == 0 ? null : pool;
        this.parallelism = Math.max(1, parallelism);
        this.governor = new PreparationGovernor(this.parallelism, TRICKLE_PERIOD);
    }

    /** Preparation on the world's build pool, or none when there is no pool or the generator prepares no regions. */
    public static RegionalPreparation open(ICubeGenerator generator, GeneratorBuildPool build, String world) {
        ForkJoinPool pool = build.pool();
        RegionalPreparation preparation = new RegionalPreparation(generator, pool, pool == null ? 1 : pool.getParallelism());
        if (preparation.pool != null)
            CubicChunks.LOGGER.info("Cubature regional preparation for {}: {}-block regions, up to {} at once", world,
                    preparation.regionBlocks, preparation.parallelism);
        return preparation;
    }

    /** One server tick of a world: players that may generate, and the native view in chunks. */
    public void tick(WorldServer world, Collection<EntityPlayerMP> players, int viewChunks) {
        if (pool == null || closed) return;
        long now = System.nanoTime();
        if (now >= nextPlan) {
            nextPlan = now + PLAN_PERIOD;
            List<Position> positions = new ArrayList<>(players.size());
            for (EntityPlayerMP player : players) positions.add(new Position(player.getUniqueID(), player.posX, player.posZ));
            WorldBorder border = world.getWorldBorder();
            plan(now, positions, CubicChunksConfig.regionalPreparationReachBlocks, viewChunks * 16,
                    (minX, minZ, maxX, maxZ) -> maxX > border.minX() && minX < border.maxX()
                            && maxZ > border.minZ() && minZ < border.maxZ(),
                    systemCpuLoad(), serverWaitMs(world.getMinecraftServer()));
        }
        pump(now);
    }

    /** Ranks the regions within reach once a second and updates the governor. */
    void plan(long now, Collection<Position> players, int reachBlocks, int nearBlocks, Bounds bounds, double cpuLoad,
            double waitMs) {
        if (pool == null) return;
        governor.update(cpuLoad, waitMs);
        Map<Long, double[]> ranked = new HashMap<>();
        Map<Object, double[]> seen = new HashMap<>();
        for (Position p : players) {
            double[] last = lastSeen.get(p.id());
            double vx = 0, vz = 0;
            if (last != null && now > last[2]) {
                double seconds = (now - last[2]) / 1e9;
                vx = (p.x() - last[0]) / seconds;
                vz = (p.z() - last[1]) / seconds;
            }
            seen.put(p.id(), new double[] {p.x(), p.z(), now});
            if (reachBlocks <= 0) continue;
            int x0 = Math.floorDiv((int) Math.floor(p.x() - reachBlocks), regionBlocks);
            int x1 = Math.floorDiv((int) Math.floor(p.x() + reachBlocks), regionBlocks);
            int z0 = Math.floorDiv((int) Math.floor(p.z() - reachBlocks), regionBlocks);
            int z1 = Math.floorDiv((int) Math.floor(p.z() + reachBlocks), regionBlocks);
            for (int rz = z0; rz <= z1; rz++)
                for (int rx = x0; rx <= x1; rx++) {
                    double minX = (double) rx * regionBlocks, minZ = (double) rz * regionBlocks;
                    double maxX = minX + regionBlocks, maxZ = minZ + regionBlocks;
                    double dx = Math.max(0, Math.max(minX - p.x(), p.x() - maxX));
                    double dz = Math.max(0, Math.max(minZ - p.z(), p.z() - maxZ));
                    double distance = Math.hypot(dx, dz);
                    if (distance > reachBlocks || !bounds.admits(minX, minZ, maxX, maxZ)) continue;
                    double cx = minX + regionBlocks / 2.0 - p.x(), cz = minZ + regionBlocks / 2.0 - p.z();
                    double length = Math.hypot(cx, cz);
                    double toward = length == 0 ? 0 : Math.max(0, (vx * cx + vz * cz) / length);
                    double seconds = distance / (toward + BASELINE_SPEED);
                    long key = ((long) rx << 32) ^ (rz & 0xffffffffL);
                    double[] best = ranked.get(key);
                    boolean isNear = distance <= nearBlocks;
                    if (best == null) ranked.put(key, new double[] {seconds, isNear ? 1 : 0});
                    else {
                        best[0] = Math.min(best[0], seconds);
                        if (isNear) best[1] = 1;
                    }
                }
        }
        lastSeen.clear();
        lastSeen.putAll(seen);
        List<Map.Entry<Long, double[]>> entries = new ArrayList<>(ranked.entrySet());
        entries.removeIf(e -> done.contains(e.getKey()));
        // Ties break by key, so the order never depends on a map's iteration.
        entries.sort((a, b) -> {
            int c = Double.compare(a.getValue()[0], b.getValue()[0]);
            return c != 0 ? c : Long.compare(a.getKey(), b.getKey());
        });
        order = new long[entries.size()];
        near = new boolean[entries.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = entries.get(i).getKey();
            near[i] = entries.get(i).getValue()[1] != 0;
        }
    }

    /** Takes in finished preparations and starts the next ones the pool, the governor and any pause allow. */
    void pump(long now) {
        for (long[] f; (f = finished.poll()) != null; ) {
            running.remove(f[0]);
            if (f[1] != 0) done.add(f[0]);
            else declinedUntil = now + DECLINED_PAUSE;
        }
        if (pool == null || closed) return;
        int outer = 0;
        for (boolean isNear : running.values()) if (!isNear) outer++;
        boolean outerHeld = now < declinedUntil;
        for (int i = 0; i < order.length && running.size() < parallelism; i++) {
            long key = order[i];
            if (done.contains(key) || running.containsKey(key)) continue;
            if (!near[i]) {
                // Once one region beyond the view may not start, none behind it jumps ahead; near ones still may.
                if (outerHeld || !governor.admits(outer, now)) {
                    outerHeld = true;
                    continue;
                }
                outer++;
            }
            running.put(key, near[i]);
            try {
                pool.execute(() -> prepare(key));
            } catch (RejectedExecutionException stopped) {
                running.remove(key);
                closed = true;
                return;
            }
        }
    }

    private void prepare(long key) {
        boolean prepared = true;
        try {
            prepared = generator.prepareRegion((int) (key >> 32), (int) key);
        } catch (RuntimeException | Error failure) {
            // Counted as prepared, so a failing region is not retried every tick.
            if (!reported) {
                reported = true;
                CubicChunks.LOGGER.error("Regional preparation failed; the region is skipped", failure);
            }
        } finally {
            finished.add(new long[] {key, prepared ? 1 : 0});
        }
    }

    /** Starts no more preparations; those running finish on the pool. */
    public void close() {
        closed = true;
    }

    int permits() {
        return governor.permits();
    }

    int running() {
        return running.size();
    }

    private static double systemCpuLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getCpuLoad() : -1;
    }

    /** The server thread's mean wait per tick since the last window: tick wall time less its own CPU time. */
    private double serverWaitMs(MinecraftServer server) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long cpu = threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : -1;
        int tick = server.getTickCounter();
        double wait = 0;
        if (cpu >= 0 && windowCpu >= 0 && tick > windowTick) {
            int ticks = Math.min(tick - windowTick, server.tickTimeArray.length);
            long wall = 0;
            for (int t = tick - ticks; t < tick; t++) wall += server.tickTimeArray[Math.floorMod(t, server.tickTimeArray.length)];
            wait = Math.max(0, (wall - (cpu - windowCpu)) / 1e6 / ticks);
        }
        windowCpu = cpu;
        windowTick = tick;
        return wait;
    }
}
