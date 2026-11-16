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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;

public class TestCubeSnapshotReads {

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
    }

    /** A cube of air with one slab at local 5 5 5, block light 3 there and 9 beside it, sky light 0. */
    private static CubeCopy slabCube(long version, long blockVersion, long lightVersion) {
        IBlockState[] blocks = new IBlockState[4096];
        Arrays.fill(blocks, Blocks.AIR.getDefaultState());
        blocks[5 << 8 | 5 << 4 | 5] = Blocks.STONE_SLAB.getDefaultState();
        byte[] blockLight = new byte[2048];
        setNibble(blockLight, 5, 5, 5, 3);
        setNibble(blockLight, 6, 5, 5, 9);
        return CubeCopy.stored(version, blockVersion, lightVersion, blocks, blockLight, new byte[2048]);
    }

    private static void setNibble(byte[] data, int x, int y, int z, int value) {
        int index = y << 8 | z << 4 | x;
        data[index >> 1] = (byte) ((index & 1) == 0 ? data[index >> 1] & 0xF0 | value
                : data[index >> 1] & 0x0F | value << 4);
    }

    private static CubeSnapshot centerOnly(CubeCopy center) {
        CubeCopy[] copies = new CubeCopy[27];
        Arrays.fill(copies, CubeCopy.NOTHING);
        copies[13] = center;
        return new CubeSnapshot(0, 0, 0, 1, true, WorldType.DEFAULT, copies);
    }

    @Test
    public void rawLightIgnoresNeighborBrightness() {
        CubeSnapshot snapshot = centerOnly(slabCube(4, 2, 4));
        BlockPos slab = new BlockPos(5, 5, 5);
        assertEquals(3, snapshot.getLightFor(EnumSkyBlock.BLOCK, slab));
        // The game's rule gives a slab the brightest of its neighbors.
        assertEquals(9, snapshot.getCombinedLight(slab, 0) >> 4 & 15);
        assertEquals(0, snapshot.getLightFor(EnumSkyBlock.SKY, slab));
    }

    @Test
    public void versionsPerOffset() {
        CubeSnapshot snapshot = centerOnly(slabCube(7, 5, 7));
        assertEquals(7, snapshot.version(0, 0, 0));
        assertEquals(5, snapshot.blockVersion(0, 0, 0));
        assertEquals(7, snapshot.lightVersion(0, 0, 0));
        assertEquals(-1, snapshot.blockVersion(1, 0, 0));
        assertEquals(-1, snapshot.lightVersion(-1, -1, -1));
    }

    @Test
    public void aCenterOnlySnapshotHoldsNoNeighbor() {
        CubeSnapshot snapshot = centerOnly(slabCube(1, 1, 1));
        assertTrue(snapshot.present(0, 0, 0));
        assertFalse(snapshot.present(1, 0, 0));
        assertEquals(Blocks.AIR.getDefaultState(), snapshot.getBlockState(new BlockPos(16, 5, 5)));
        assertEquals(15, snapshot.getLightFor(EnumSkyBlock.SKY, new BlockPos(16, 5, 5)));
        assertEquals(0, snapshot.getLightFor(EnumSkyBlock.BLOCK, new BlockPos(16, 5, 5)));
    }

    /** A source in which only the given cubes are loaded. */
    private static ICubeSource loadedOnly(java.util.Set<Long> loaded) {
        return new ICubeSource() {
            @Override public void setListener(io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener l) { }
            @Override public boolean attached() { return true; }
            @Override public long worldIdentity() { return 1; }
            @Override public int minCubeY() { return -8; }
            @Override public int maxCubeY() { return 8; }
            @Override public boolean hasSkyLight() { return true; }
            @Override public boolean isLoaded(int x, int y, int z) { return loaded.contains(key(x, y, z)); }
            @Override public long version(int x, int y, int z) { return 0; }
            @Override public void forEachLoaded(CubeVisitor visitor) { }
            @Override public io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot snapshot(int x, int y, int z) {
                throw new UnsupportedOperationException();
            }
            @Override public io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview terrainPreview() {
                return null;
            }
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | (long) z & 0xFFFFF;
    }

    @Test
    public void loadedNeighborsMasksWhatIsLoaded() {
        java.util.Set<Long> loaded = new java.util.HashSet<>();
        loaded.add(key(0, 0, 0));
        loaded.add(key(1, 0, 0));
        loaded.add(key(-1, 1, 1));
        loaded.add(key(5, 5, 5));
        int mask = loadedOnly(loaded).loadedNeighbors(0, 0, 0);
        assertEquals(ICubeSource.NEIGHBOR_CENTER | ICubeSource.neighborBit(1, 0, 0) | ICubeSource.neighborBit(-1, 1, 1),
                mask);
        assertEquals(2, Integer.bitCount(mask & ICubeSource.NEIGHBOR_ALL));
        assertEquals(ICubeSource.neighborBit(1, 0, 0), mask & ICubeSource.NEIGHBOR_FACES);
    }

    @Test
    public void neighborBitsFollowThePresentOrder() {
        assertEquals(ICubeSource.NEIGHBOR_CENTER, ICubeSource.neighborBit(0, 0, 0));
        int faces = ICubeSource.neighborBit(-1, 0, 0) | ICubeSource.neighborBit(1, 0, 0)
                | ICubeSource.neighborBit(0, -1, 0) | ICubeSource.neighborBit(0, 1, 0)
                | ICubeSource.neighborBit(0, 0, -1) | ICubeSource.neighborBit(0, 0, 1);
        assertEquals(faces, ICubeSource.NEIGHBOR_FACES);
        assertEquals(26, Integer.bitCount(ICubeSource.NEIGHBOR_ALL));
        assertEquals(0, ICubeSource.NEIGHBOR_ALL & ICubeSource.NEIGHBOR_CENTER);
    }
}
