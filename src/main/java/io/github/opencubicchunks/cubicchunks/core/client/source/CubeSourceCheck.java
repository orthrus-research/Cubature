/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.client.source;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;

/**
 * A diagnostic, off unless {@code -Dcubicchunks.cubeSourceCheck=true}: listens to the cube source as a
 * renderer would and checks its guarantees against the live world. Every cube that loads or changes
 * is snapshotted; some ticks later, if the cube's version has not moved, its copy is compared with
 * the cube again. A difference then is a change that raised no event.
 */
public final class CubeSourceCheck implements ICubeSourceListener {

    public static final boolean ENABLED = Boolean.getBoolean("cubicchunks.cubeSourceCheck");

    private static final int DELAY_TICKS = 40;
    private static final int PER_TICK = 64;
    /**
     * Ticks after an unload by which its render chunk has been rebuilt: two seconds, well past the
     * game's rebuild latency, so a mesh still drawn then is one that was never going to go.
     */
    private static final int MESH_DELAY_TICKS = 40;
    private static final int REPORT_TICKS = 200;

    private static final class Held {
        final ICubeSnapshot snapshot;
        final long dueTick;

        Held(ICubeSnapshot snapshot, long dueTick) {
            this.snapshot = snapshot;
            this.dueTick = dueTick;
        }
    }

    private static final class Departed {
        final int x;
        final int y;
        final int z;
        final long dueTick;

        Departed(int x, int y, int z, long dueTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dueTick = dueTick;
        }
    }

    private final ClientCubeSource source = ClientCubeSource.INSTANCE;
    private final ArrayDeque<Held> held = new ArrayDeque<>();
    private final ArrayDeque<Departed> departed = new ArrayDeque<>();
    private long tick;
    private long loaded;
    private long unloaded;
    private long changed;
    private long neighborOnly;
    private long snapshots;
    private long verified;
    private long superseded;
    private long stale;
    private long accessMismatches;
    private long absentNeighbor;
    private long goneBeforeDelivery;
    private long worlds;
    private long departedChecked;
    private long departedReturned;
    private long departedOutsideGrid;
    private long staleMeshes;
    private long orphans;
    /** The tick the current held box was stated, and whether it has settled since. */
    private long boxTick = -1;
    private boolean boxSettled;
    private long boxes;
    private long boxesSent;
    private long boxesSettled;
    private long sentTicks;
    private long settledTicks;
    private long maxSettledTicks;
    /** The build pool, exercised as a background mesher would: one task a cube, nearest first. */
    private final java.util.Map<Long, io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork.Task<?>> builds =
            new java.util.HashMap<>();
    private final java.util.Map<io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork.Task<?>, Long> submitted =
            new java.util.IdentityHashMap<>();
    private long buildsSubmitted;
    private long buildsReplaced;
    private long buildsDone;
    private long buildsFailed;
    private long buildsWrongVersion;
    private long buildLatencyTicks;
    private long buildMaxLatencyTicks;
    private int buildMaxQueued;
    private int buildMaxAdmitted;
    private int buildMaxFinished;

    @Override
    public void worldAttached(long worldIdentity) {
        worlds++;
        held.clear();
        departed.clear();
        source.forEachLoaded(this::take);
        CubicChunks.LOGGER.info("Cube source check: world {} attached, {} cubes already loaded, heights {} to {}",
                worldIdentity, held.size(), source.minCubeY(), source.maxCubeY());
    }

    @Override
    public void worldDetached(long worldIdentity) {
        held.clear();
        departed.clear();
        report("world " + worldIdentity + " detached");
    }

    @Override
    public void heldBoxChanged() {
        boxes++;
        boxTick = tick;
        boxSettled = false;
    }

    @Override
    public void heldBoxSent() {
        if (boxTick >= 0) {
            boxesSent++;
            sentTicks += tick - boxTick;
        }
    }

    /** Ticks from a box being stated to the view settling (20 quiet ticks with the box sent). */
    private void followSettled() {
        if (boxTick < 0 || boxSettled || !source.settled(20)) {
            return;
        }
        boxSettled = true;
        boxesSettled++;
        long took = tick - boxTick;
        settledTicks += took;
        maxSettledTicks = Math.max(maxSettledTicks, took);
        CubicChunks.LOGGER.info("Cube source check: settled {} ticks after the held box {} was stated",
                took, java.util.Arrays.toString(source.heldBox()));
    }

    @Override
    public void cubeLoaded(int cubeX, int cubeY, int cubeZ) {
        loaded++;
        if (!source.isLoaded(cubeX, cubeY, cubeZ)) {
            // It arrived and left within one tick; its removal follows in this same delivery.
            goneBeforeDelivery++;
            return;
        }
        take(cubeX, cubeY, cubeZ);
    }

    @Override
    public void cubeUnloaded(int cubeX, int cubeY, int cubeZ) {
        unloaded++;
        departed.add(new Departed(cubeX, cubeY, cubeZ, tick + MESH_DELAY_TICKS));
    }

    /**
     * Whether the vanilla renderer still draws a mesh for a cube that left: its render chunk, if the
     * grid still covers the cube, compiled something. Only the vanilla terrain path is read.
     */
    private void checkDeparted(Departed cube) {
        if (source.isLoaded(cube.x, cube.y, cube.z)) {
            departedReturned++;
            return;
        }
        net.minecraft.client.renderer.RenderGlobal renderer = Minecraft.getMinecraft().renderGlobal;
        net.minecraft.client.renderer.ViewFrustum frustum = renderer == null ? null
                : ((io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.client.IRenderGlobal) renderer)
                        .getViewFrustum();
        if (frustum == null) {
            return;
        }
        BlockPos origin = new BlockPos(cube.x << 4, cube.y << 4, cube.z << 4);
        net.minecraft.client.renderer.chunk.RenderChunk chunk =
                ((io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.client.IViewFrustum) frustum)
                        .getRenderChunkAt(origin);
        if (chunk == null || !chunk.getPosition().equals(origin)) {
            departedOutsideGrid++;
            return;
        }
        departedChecked++;
        if (!chunk.getCompiledChunk().isEmpty()) {
            if (staleMeshes++ < 10) {
                CubicChunks.LOGGER.error("Cube source check: cube {} {} {} left {} ticks ago and its mesh is still drawn",
                        cube.x, cube.y, cube.z, MESH_DELAY_TICKS);
            }
        }
    }

    /** Cubes the client still holds in a column it no longer has. */
    private long countOrphans() {
        long count = 0;
        io.github.opencubicchunks.cubicchunks.core.client.CubeProviderClient cubes = source.cubes();
        if (cubes == null) {
            return 0;
        }
        for (Cube cube : cubes.loadedCubes()) {
            if (cubes.getLoadedColumn(cube.getX(), cube.getZ()) != cube.getColumn()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void cubeChanged(int cubeX, int cubeY, int cubeZ, int kinds, int minX, int minY, int minZ, int maxX,
            int maxY, int maxZ) {
        changed++;
        if (kinds == NEIGHBOR) {
            neighborOnly++;
        } else {
            take(cubeX, cubeY, cubeZ);
        }
    }

    private void take(int x, int y, int z) {
        ICubeSnapshot snapshot = source.snapshot(x, y, z);
        snapshots++;
        boolean complete = true;
        for (int i = 0; i < 27; i++) {
            complete &= snapshot.present(i / 9 - 1, i / 3 % 3 - 1, i % 3 - 1);
        }
        if (compareAccess(snapshot, x, y, z, complete) != 0) {
            // Where a neighboring cube is not loaded the two are meant to differ: the game answers full
            // sky light for it, the snapshot the sky light an empty cube there would have.
            if (complete) {
                accessMismatches++;
            } else {
                absentNeighbor++;
            }
        }
        held.add(new Held(snapshot, tick + DELAY_TICKS));
        submitBuild(x, y, z);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | (long) z & 0x1FFFFF;
    }

    /** The squared distance in cubes from the player, doubled behind the player's facing. */
    private static long priority(int x, int y, int z) {
        net.minecraft.entity.Entity viewer = Minecraft.getMinecraft().getRenderViewEntity();
        if (viewer == null) {
            return 0;
        }
        double dx = (x << 4) + 8 - viewer.posX;
        double dy = (y << 4) + 8 - viewer.posY;
        double dz = (z << 4) + 8 - viewer.posZ;
        long distance = (long) ((dx * dx + dy * dy + dz * dz) / 256);
        net.minecraft.util.math.Vec3d look = viewer.getLookVec();
        return dx * look.x + dy * look.y + dz * look.z < 0 ? distance * 2 : distance;
    }

    /** Hands in a build of the cube's blocks, replacing one still waiting or running for it. */
    private void submitBuild(int x, int y, int z) {
        io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork work = source.buildWork();
        if (work == null) {
            return;
        }
        io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork.Task<?> old = builds.remove(key(x, y, z));
        if (old != null) {
            old.cancel();
            submitted.remove(old);
            buildsReplaced++;
        }
        io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork.Task<Integer> task = work.submit(
                priority(x, y, z), x, y, z, false,
                io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork.STALE_ON_CENTER, (snapshot, self) -> {
                    // What a mesher reads: every block of the center, and its light.
                    int solid = 0;
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    for (int i = 0; i < 4096; i++) {
                        pos.setPos((x << 4) + (i & 15), (y << 4) + (i >> 8), (z << 4) + (i >> 4 & 15));
                        if (!snapshot.isAirBlock(pos)) {
                            solid++;
                        }
                        if ((i & 1023) == 1023) {
                            if (self.cancelled()) {
                                return -1;
                            }
                            self.checkpoint();
                        }
                    }
                    return solid;
                });
        builds.put(key(x, y, z), task);
        submitted.put(task, tick);
        buildsSubmitted++;
    }

    /** Takes back finished builds and rescores the waiting ones as the player moves. */
    private void followBuilds() {
        io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork work = source.buildWork();
        if (work == null) {
            return;
        }
        buildMaxQueued = Math.max(buildMaxQueued, work.queued());
        buildMaxAdmitted = Math.max(buildMaxAdmitted, work.admitted());
        buildMaxFinished = Math.max(buildMaxFinished, work.finished());
        for (io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork.Task<?> task = work.poll(); task != null;
                task = work.poll()) {
            Long at = submitted.remove(task);
            builds.remove(key(task.cubeX(), task.cubeY(), task.cubeZ()), task);
            if (task.state() == io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork.FAILED) {
                if (buildsFailed++ < 5) {
                    CubicChunks.LOGGER.error("Cube source check: a build failed", task.failure());
                }
                continue;
            }
            buildsDone++;
            if (task.snapshotVersion() != source.version(task.cubeX(), task.cubeY(), task.cubeZ())) {
                // A stale task never comes back: a finished one must match the cube as it stands.
                buildsWrongVersion++;
            }
            if (at != null) {
                long took = tick - at;
                buildLatencyTicks += took;
                buildMaxLatencyTicks = Math.max(buildMaxLatencyTicks, took);
            }
        }
        if (tick % 20 == 0) {
            work.reprioritizeAll(task -> priority(task.cubeX(), task.cubeY(), task.cubeZ()));
        }
        // Tasks the pool made stale or dropped never come back: forget them.
        if (tick % 200 == 0) {
            submitted.keySet().removeIf(task -> task.cancelled());
            builds.values().removeIf(task -> task.cancelled());
        }
    }

    /** The snapshot's block access against the world's, over the center cube's corners and middle. */
    private int compareAccess(ICubeSnapshot snapshot, int x, int y, int z, boolean log) {
        Cube cube = source.loaded(x, y, z);
        if (cube == null) {
            return 0;
        }
        int mismatches = 0;
        // The game's own render cache over the same neighborhood: what its renderer would read.
        net.minecraft.world.ChunkCache reference = new net.minecraft.world.ChunkCache(cube.getWorld(),
                new BlockPos((x << 4) - 1, (y << 4) - 1, (z << 4) - 1),
                new BlockPos((x << 4) + 16, (y << 4) + 16, (z << 4) + 16), 0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 9; i++) {
            pos.setPos((x << 4) + (i == 8 ? 7 : (i & 1) * 15), (y << 4) + (i == 8 ? 7 : (i >> 1 & 1) * 15),
                    (z << 4) + (i == 8 ? 7 : (i >> 2) * 15));
            if (snapshot.getBlockState(pos) != reference.getBlockState(pos)
                    || snapshot.getCombinedLight(pos, 0) != reference.getCombinedLight(pos, 0)) {
                if (mismatches++ == 0 && log && accessMismatches == 0) {
                    CubicChunks.LOGGER.error("Cube source check: snapshot differs from the game's render cache at {}: {} light {} "
                                    + "against {} light {}", pos, snapshot.getBlockState(pos),
                            Integer.toHexString(snapshot.getCombinedLight(pos, 0)),
                            reference.getBlockState(pos),
                            Integer.toHexString(reference.getCombinedLight(pos, 0)));
                }
            }
        }
        return mismatches;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        tick++;
        for (int i = 0; i < PER_TICK && !held.isEmpty() && held.peek().dueTick <= tick; i++) {
            ICubeSnapshot snapshot = held.poll().snapshot;
            if (snapshot.worldIdentity() != source.worldIdentity()) {
                continue;
            }
            Cube cube = source.loaded(snapshot.centerX(), snapshot.centerY(), snapshot.centerZ());
            if (cube == null || cube.sourceVersion != snapshot.version(0, 0, 0)) {
                superseded++;
                continue;
            }
            verified++;
            Object copy = cube.sourceCopy;
            String difference = copy instanceof CubeCopy && ((CubeCopy) copy).version == cube.sourceVersion
                    ? ((CubeCopy) copy).difference(cube, source.hasSkyLight()) : null;
            if (difference != null) {
                if (stale++ < 10) {
                    CubicChunks.LOGGER.error("Cube source check: cube {} {} {} changed with no event: {}",
                            cube.getX(), cube.getY(), cube.getZ(), difference);
                }
                // Count it once: a further check of the same copy would report the same difference.
                cube.sourceCopy = null;
            }
        }
        for (int i = 0; i < PER_TICK && !departed.isEmpty() && departed.peek().dueTick <= tick; i++) {
            checkDeparted(departed.poll());
        }
        followSettled();
        followBuilds();
        if (tick % REPORT_TICKS == 0 && worlds > 0) {
            orphans = countOrphans();
            report("tick " + tick);
        }
    }

    private void report(String when) {
        CubicChunks.LOGGER.info("Cube source check, {}: events loaded {} unloaded {} changed {} (neighbor only {}); "
                        + "snapshots {}, differing from the game's render cache {}, differing only beside an absent cube {}, gone before delivery {}; rechecked {} unchanged cubes, {} changed with no "
                        + "event; {} superseded, {} waiting; departed cubes {} back, {} outside the render grid, {} checked, {} still drawn; {} cubes held without their column; "
                        + "held boxes {}, sent {} (mean {} ticks), settled {} (mean {}, longest {} ticks); "
                        + "builds submitted {}, replaced {}, done {} (mean {} ticks, longest {}), failed {}, done with a moved "
                        + "version {}, most queued {}, most holding a snapshot {}, most finished awaiting poll {}",
                when, loaded, unloaded, changed, neighborOnly, snapshots,
                accessMismatches, absentNeighbor, goneBeforeDelivery, verified, stale, superseded, held.size(),
                departedReturned, departedOutsideGrid, departedChecked, staleMeshes, orphans, boxes, boxesSent,
                boxesSent == 0 ? 0 : sentTicks / boxesSent, boxesSettled,
                boxesSettled == 0 ? 0 : settledTicks / boxesSettled, maxSettledTicks, buildsSubmitted, buildsReplaced,
                buildsDone, buildsDone == 0 ? 0 : buildLatencyTicks / buildsDone, buildMaxLatencyTicks, buildsFailed,
                buildsWrongVersion, buildMaxQueued, buildMaxAdmitted, buildMaxFinished);
    }
}
