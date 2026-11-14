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
    private static final int REPORT_TICKS = 200;

    private static final class Held {
        final ICubeSnapshot snapshot;
        final long dueTick;

        Held(ICubeSnapshot snapshot, long dueTick) {
            this.snapshot = snapshot;
            this.dueTick = dueTick;
        }
    }

    private final ClientCubeSource source = ClientCubeSource.INSTANCE;
    private final ArrayDeque<Held> held = new ArrayDeque<>();
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

    @Override
    public void worldAttached(long worldIdentity) {
        worlds++;
        held.clear();
        source.forEachLoaded(this::take);
        CubicChunks.LOGGER.info("Cube source check: world {} attached, {} cubes already loaded, heights {} to {}",
                worldIdentity, held.size(), source.minCubeY(), source.maxCubeY());
    }

    @Override
    public void worldDetached(long worldIdentity) {
        held.clear();
        report("world " + worldIdentity + " detached");
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
        if (tick % REPORT_TICKS == 0 && worlds > 0) {
            report("tick " + tick);
        }
    }

    private void report(String when) {
        CubicChunks.LOGGER.info("Cube source check, {}: events loaded {} unloaded {} changed {} (neighbor only {}); "
                        + "snapshots {}, differing from the game's render cache {}, differing only beside an absent cube {}, gone before delivery {}; rechecked {} unchanged cubes, {} changed with no "
                        + "event; {} superseded, {} waiting", when, loaded, unloaded, changed, neighborOnly, snapshots,
                accessMismatches, absentNeighbor, goneBeforeDelivery, verified, stale, superseded, held.size());
    }
}
