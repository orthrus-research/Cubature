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
package io.github.opencubicchunks.cubicchunks.core.lighting;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import javax.annotation.Nullable;

/**
 * A diagnostic hash of a cube's light, the same on the server and on the client, so that two copies
 * of a cube can be compared without moving their light: block light and sky light of every block,
 * read as the cube answers them (a cube without storage answers its implicit sky light).
 *
 * <p>With {@code -Dcubicchunks.lightChecksumDump=<directory>} each side writes the checksums of every
 * cube it holds: the server as it stops, the client every 200 ticks and as its world goes (a client
 * may be closed without its world going).
 */
public final class LightChecksum {

    /** Where the dumps go, or null when none is asked for. */
    @Nullable public static final Path DUMP = dumpDirectory();
    /** With a dump: the server also writes one every this many world ticks, named by the tick; 0 never. */
    public static final int EVERY = Integer.getInteger("cubicchunks.lightChecksumEvery", 0);
    /** With a dump: cubes, one {@code x y z} a line in this file, whose every block's light the server also writes. */
    @Nullable private static final java.util.Set<Long> DETAIL = detailCubes();

    private LightChecksum() {
    }

    @Nullable
    private static java.util.Set<Long> detailCubes() {
        String named = System.getProperty("cubicchunks.lightDetail");
        if (named == null || named.isEmpty()) {
            return null;
        }
        java.util.Set<Long> cubes = new java.util.HashSet<>();
        try {
            for (String line : Files.readAllLines(Paths.get(named), StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 3) {
                    cubes.add(key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                }
            }
        } catch (IOException | NumberFormatException e) {
            CubicChunks.LOGGER.error("Light checksums: could not read the cubes to detail from {}", named, e);
        }
        return cubes;
    }

    @Nullable
    private static Path dumpDirectory() {
        String named = System.getProperty("cubicchunks.lightChecksumDump");
        return named == null || named.isEmpty() ? null : Paths.get(named);
    }

    /** A cube's position as one key, for the server's count of light changes after a send. */
    public static long key(Cube cube) {
        return key(cube.getX(), cube.getY(), cube.getZ());
    }

    /** 21 bits a coordinate, enough for any cube position a world can hold. */
    static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | (long) z & 0x1FFFFF;
    }

    static int keyX(long key) {
        return (int) (key << 1 >> 43);
    }

    static int keyY(long key) {
        return (int) (key << 22 >> 43);
    }

    static int keyZ(long key) {
        return (int) (key << 43 >> 43);
    }

    /**
     * Writes the server's count of light changes after a send, {@code <dump>/server-lightafter-<dimension>.tsv}:
     * cube X, Y and Z and the ticks in which its light changed after it was sent, then the totals.
     */
    public static void dumpLightAfterSend(int dimension, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap counts,
            long[] totals) {
        if (DUMP == null) {
            return;
        }
        Path out = DUMP.resolve("server-lightafter-" + dimension + ".tsv");
        try {
            Files.createDirectories(DUMP);
            try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                writer.write("x\ty\tz\tticks\n");
                for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry entry : counts.long2IntEntrySet()) {
                    long key = entry.getLongKey();
                    writer.write(keyX(key) + "\t" + keyY(key) + "\t" + keyZ(key) + "\t" + entry.getIntValue() + "\n");
                }
            }
            CubicChunks.LOGGER.info("Light checksums: {} cubes changed light after they were sent ({} changes); {} light "
                    + "sends, {} bytes; written to {}", counts.size(), totals[0], totals[1], totals[2], out);
        } catch (IOException e) {
            CubicChunks.LOGGER.error("Light checksums: could not write {}", out, e);
        }
    }

    /** Every block of the listed cubes: cube, local position, block light, sky light, state id. */
    private static void writeDetail(Path out, Iterable<Cube> cubes) throws IOException {
        try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write("cx\tcy\tcz\tx\ty\tz\tblock\tsky\tstate\n");
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (Cube cube : cubes) {
                if (!DETAIL.contains(key(cube))) {
                    continue;
                }
                ExtendedBlockStorage storage = cube.getStorage();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            pos.setPos((cube.getX() << 4) + x, (cube.getY() << 4) + y, (cube.getZ() << 4) + z);
                            writer.write(cube.getX() + "\t" + cube.getY() + "\t" + cube.getZ() + "\t" + x + "\t" + y + "\t"
                                    + z + "\t" + cube.getCachedLightFor(EnumSkyBlock.BLOCK, pos) + "\t"
                                    + cube.getCachedLightFor(EnumSkyBlock.SKY, pos) + "\t"
                                    + (storage == null ? 0 : Block.getStateId(storage.get(x, y, z))) + "\n");
                        }
                    }
                }
            }
        }
    }

    /** The checksum of a loaded cube: never negative, so never -1. */
    public static long of(Cube cube) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = cube.getX() << 4;
        int baseY = cube.getY() << 4;
        int baseZ = cube.getZ() << 4;
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.setPos(baseX + x, baseY + y, baseZ + z);
                    int light = cube.getCachedLightFor(EnumSkyBlock.BLOCK, pos) << 4
                            | cube.getCachedLightFor(EnumSkyBlock.SKY, pos);
                    hash = (hash ^ light) * 0x100000001b3L;
                }
            }
        }
        return hash & Long.MAX_VALUE;
    }

    /** A hash of the cube's block states, to tell a difference of light from a difference of blocks. */
    public static long blocks(Cube cube) {
        long hash = 0xcbf29ce484222325L;
        ExtendedBlockStorage storage = cube.getStorage();
        if (storage != null) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        hash = (hash ^ Block.getStateId(storage.get(x, y, z))) * 0x100000001b3L;
                    }
                }
            }
        }
        return hash & Long.MAX_VALUE;
    }

    /**
     * Writes one line per cube to {@code <dump>/<side>-<dimension>.tsv}: cube X, Y and Z, the checksum,
     * the block checksum, whether the cube has block storage and whether its first light is done.
     */
    public static void dump(String side, int dimension, Iterable<Cube> cubes) {
        dump(side + "-" + dimension, side, cubes);
    }

    /** A server dump named by world tick, {@code server-<dimension>-t<tick>.tsv}, every {@link #EVERY} ticks. */
    public static void dumpAt(int dimension, long worldTick, Iterable<Cube> cubes) {
        if (DUMP != null && EVERY > 0 && worldTick % EVERY == 0) {
            dump("server-" + dimension + "-t" + worldTick, "server", cubes);
        }
    }

    /** Pending checks of a cube's boundaries, sky and block, as bit counts: light waiting for a neighbor. */
    private static String pendingChecks(Cube cube) {
        Object data = cube.getCubeLightData();
        if (!(data instanceof io.github.opencubicchunks.cubicchunks.core.lighting.LightingManager.CubeLightData)) {
            return "0\t0";
        }
        io.github.opencubicchunks.cubicchunks.core.lighting.LightingManager.CubeLightData light =
                (io.github.opencubicchunks.cubicchunks.core.lighting.LightingManager.CubeLightData) data;
        return Long.bitCount(light.neighborLightChecksSky) + "\t" + Long.bitCount(light.neighborLightChecksBlock);
    }

    private static void dump(String name, String side, Iterable<Cube> cubes) {
        if (DUMP == null) {
            return;
        }
        Path out = DUMP.resolve(name + ".tsv");
        Path temporary = DUMP.resolve(name + ".tsv.tmp");
        Path detail = DUMP.resolve(name + "-detail.tsv");
        int count = 0;
        try {
            Files.createDirectories(DUMP);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("x\ty\tz\tchecksum\tblocks\tstorage\tfirstLight\tpendingSky\tpendingBlock\n");
                for (Cube cube : cubes) {
                    writer.write(cube.getX() + "\t" + cube.getY() + "\t" + cube.getZ() + "\t"
                            + Long.toHexString(of(cube)) + "\t" + Long.toHexString(blocks(cube)) + "\t"
                            + (cube.getStorage() != null ? 1 : 0) + "\t"
                            + (cube.isInitialLightingDone() ? 1 : 0) + "\t" + pendingChecks(cube) + "\n");
                    count++;
                }
            }
            if (DETAIL != null && !DETAIL.isEmpty()) {
                writeDetail(detail, cubes);
            }
            // A client that is closed while writing leaves its last whole dump in place.
            Files.move(temporary, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (side.equals("server")) {
                CubicChunks.LOGGER.info("Light checksums: {} cubes written to {}", count, out);
            }
        } catch (IOException e) {
            CubicChunks.LOGGER.error("Light checksums: could not write {}", out, e);
        }
    }
}
