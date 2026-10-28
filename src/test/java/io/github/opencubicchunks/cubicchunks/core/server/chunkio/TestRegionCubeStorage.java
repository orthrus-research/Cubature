package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import cubicchunks.regionlib.impl.SaveCubeColumns;
import cubicchunks.regionlib.impl.EntryLocation3D;
import net.minecraft.nbt.CompressedStreamTools;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.*;

public class TestRegionCubeStorage {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @Test public void compressionFailureReleasesEarlierBuffersAndWritesNothing() throws Exception {
        try (TrackedStore storage = new TrackedStore(temporary.newFolder().toPath())) {
            storage.failAt = 3;
            Map<CubePos, NBTTagCompound> cubes = new LinkedHashMap<>();
            for (int i = 0; i < 4; i++) cubes.put(new CubePos(0, i - 8, 0), TestPendingCubeWrites.tag(i));
            try { storage.writeBatch(new ICubicStorage.NBTBatch(Collections.singletonMap(new ChunkPos(0, 0), TestPendingCubeWrites.tag(1)), cubes)); fail(); }
            catch (IOException expected) { assertEquals("compression failed", expected.getMessage()); }
            assertEquals(2, storage.buffers.size());
            storage.buffers.forEach(buffer -> assertEquals(0, buffer.refCnt()));
            assertNull(storage.readColumn(new ChunkPos(0, 0))); assertNull(storage.readCube(new CubePos(0, -8, 0)));
        }
    }
    @Test public void successfulWritesReleaseBuffersAndRoundTripAfterClose() throws Exception {
        Path path = temporary.newFolder().toPath(); CubePos cube = new CubePos(-1, 23, 0); ChunkPos column = new ChunkPos(-1, 0);
        NBTTagCompound original = TestPendingCubeWrites.tag(42); original.setByteArray("data", new byte[] {0, 1, 2});
        try (TrackedStore storage = new TrackedStore(path)) {
            storage.writeBatch(new ICubicStorage.NBTBatch(Collections.singletonMap(column, original), Collections.singletonMap(cube, original)));
            storage.flush(); storage.buffers.forEach(buffer -> assertEquals(0, buffer.refCnt()));
        }
        try (RegionCubeStorage reopened = new RegionCubeStorage(path)) {
            assertEquals(original, reopened.readColumn(column)); assertEquals(original, reopened.readCube(cube));
            assertFalse(reopened.cubeExists(new CubePos(-1, -8, 0)));
        }
    }
    @Test public void runtimeKeyMappingFailureReleasesEarlierBuffers() throws Exception {
        try (TrackedStore storage = new TrackedStore(temporary.newFolder().toPath())) {
            Map<Integer, NBTTagCompound> values = new LinkedHashMap<>(); values.put(0, TestPendingCubeWrites.tag(0)); values.put(1, TestPendingCubeWrites.tag(1));
            try { storage.compressNBTForBatchWrite(values, key -> { if (key == 1) throw new IllegalArgumentException("key failed"); return key; }); fail(); }
            catch (IllegalArgumentException expected) { }
            assertEquals(1, storage.buffers.size()); assertEquals(0, storage.buffers.get(0).refCnt());
        }
    }
    @Test public void bothFactoriesRoundTripNormalOverflowAndEnumerationBeyondCapacity() throws Exception {
        boolean originalMode = CubicChunksConfig.useShadowPagingIO;
        try {
            for (boolean shadow : new boolean[] {true, false}) {
                CubicChunksConfig.useShadowPagingIO = shadow;
                Path path = temporary.newFolder().toPath();
                Map<CubePos, NBTTagCompound> expected = new LinkedHashMap<>();
                for (int i = 0; i < 270; i++) expected.put(new CubePos(i * 16 - 2048, -17, 32), TestPendingCubeWrites.tag(i));
                CubePos overflow = new CubePos(-32, -32, -32);
                NBTTagCompound large = TestPendingCubeWrites.tag(999);
                byte[] bytes = new byte[180000]; new Random(90321).nextBytes(bytes); large.setByteArray("incompressible", bytes);
                expected.put(overflow, large);
                ChunkPos column = new ChunkPos(-32, 32);
                try (RegionCubeStorage store = new RegionCubeStorage(path)) {
                    for (Map.Entry<CubePos, NBTTagCompound> entry : expected.entrySet()) store.writeCube(entry.getKey(), entry.getValue());
                    store.writeColumn(column, large); store.flush();
                    for (int pass = 0; pass < 3; pass++) {
                        Set<CubePos> found = new HashSet<>();
                        store.forEachCube(pos -> assertTrue("duplicate " + pos, found.add(pos)));
                        assertEquals(expected.keySet(), found);
                        Set<ChunkPos> columns = new HashSet<>(); store.forEachColumn(columns::add);
                        assertEquals(Collections.singleton(column), columns);
                    }
                }
                try (RegionCubeStorage reopened = new RegionCubeStorage(path)) {
                    for (Map.Entry<CubePos, NBTTagCompound> entry : expected.entrySet()) assertEquals(entry.getValue(), reopened.readCube(entry.getKey()));
                    assertEquals(large, reopened.readColumn(column));
                    // Move overflow data back into the normal file, then out again.
                    reopened.writeCube(overflow, TestPendingCubeWrites.tag(11));
                    assertEquals(TestPendingCubeWrites.tag(11), reopened.readCube(overflow));
                    reopened.writeCube(overflow, large); reopened.flush();
                }
                // The unchanged dependency reader can reopen both formats after owned storage closes.
                try (SaveCubeColumns legacy = SaveCubeColumns.create(path)) {
                    byte[] stored = legacy.load(new EntryLocation3D(overflow.getX(), overflow.getY(), overflow.getZ()), true).get().array();
                    assertEquals(large, CompressedStreamTools.readCompressed(new ByteArrayInputStream(stored)));
                }
            }
        } finally { CubicChunksConfig.useShadowPagingIO = originalMode; }
    }

    @Test public void closingOneActualStorageLeavesTheOtherReadableWritableAndFlushable() throws Exception {
        Path aPath = temporary.newFolder().toPath(), bPath = temporary.newFolder().toPath();
        CubePos pos = new CubePos(1, -18, 3);
        try (RegionCubeStorage a = new RegionCubeStorage(aPath); RegionCubeStorage b = new RegionCubeStorage(bPath)) {
            a.writeCube(pos, TestPendingCubeWrites.tag(1)); b.writeCube(pos, TestPendingCubeWrites.tag(2));
            a.flush(); a.close();
            assertEquals(TestPendingCubeWrites.tag(2), b.readCube(pos));
            b.writeCube(pos, TestPendingCubeWrites.tag(3)); b.flush();
        }
        try (RegionCubeStorage reopened = new RegionCubeStorage(bPath)) { assertEquals(TestPendingCubeWrites.tag(3), reopened.readCube(pos)); }
    }
    private static class TrackedStore extends RegionCubeStorage {
        final List<ByteBuf> buffers = new ArrayList<>(); int calls, failAt = -1;
        TrackedStore(Path path) throws IOException { super(path); }
        @Override protected ByteBuf compressNBT(NBTTagCompound nbt) throws IOException {
            if (++calls == failAt) throw new IOException("compression failed");
            ByteBuf buffer = super.compressNBT(nbt); buffers.add(buffer); return buffer;
        }
    }
}
