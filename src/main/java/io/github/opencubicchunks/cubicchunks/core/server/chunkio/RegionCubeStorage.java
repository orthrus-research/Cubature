/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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
package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import cubicchunks.regionlib.api.region.key.IKey;
import cubicchunks.regionlib.api.region.key.IKeyProvider;
import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.SaveCubeColumns;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import cubicchunks.regionlib.lib.ExtRegion;
import cubicchunks.regionlib.util.Utils;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.storage.IRetryableCloseStorage;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.ShadowPagingRegion;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.RegionHandleCache;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.LegacyRegionFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.UnpooledByteBufAllocator;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ICubicStorage} for the Cubic Chunks' standard Anvil3d storage format.
 */
public class RegionCubeStorage implements IRetryableCloseStorage {
    private final RegionHandleCache regions = new RegionHandleCache(256);
    private final SaveCubeColumns save;
    private final RegionHandleCache.Provider<EntryLocation2D> columns, overflowColumns;
    private final RegionHandleCache.Provider<EntryLocation3D> cubes, overflowCubes;

    @SuppressWarnings("unchecked")
    public RegionCubeStorage(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Utils.createDirectories(path);
        Path part2d = path.resolve("region2d"), part3d = path.resolve("region3d");
        Utils.createDirectories(part2d);
        Utils.createDirectories(part3d);
        columns = normalProvider(part2d, new EntryLocation2D.Provider());
        overflowColumns = overflowProvider(part2d, new EntryLocation2D.Provider());
        cubes = normalProvider(part3d, new EntryLocation3D.Provider());
        overflowCubes = overflowProvider(part3d, new EntryLocation3D.Provider());
        save = new SaveCubeColumns(new SaveSection2D(columns, overflowColumns), new SaveSection3D(cubes, overflowCubes));
    }

    private <K extends IKey<K>> RegionHandleCache.Provider<K> normalProvider(Path path, IKeyProvider<K> keys) {
        boolean shadow = CubicChunksConfig.useShadowPagingIO;
        return regions.provider(path, keys, false, (keyProvider, key) -> shadow
                ? ShadowPagingRegion.<K>builder().setDirectory(path).setRegionKey(key).setKeyProvider(keyProvider).setSectorSize(512).build()
                : LegacyRegionFactory.open(path, keyProvider, key));
    }

    private <K extends IKey<K>> RegionHandleCache.Provider<K> overflowProvider(Path path, IKeyProvider<K> keys) {
        return regions.provider(path, keys, true,
                (keyProvider, key) -> new ExtRegion<>(path, Collections.emptyList(), keyProvider, key));
    }

    private static <K extends IKey<K>> void forEach(RegionHandleCache.Provider<K> normal,
            RegionHandleCache.Provider<K> overflow, Consumer<K> callback) throws IOException {
        normal.forAllKeys(callback::accept);
        overflow.forAllKeys(key -> {
            if (!normal.fromExistingRegion(key, region -> region.hasValue(key)).orElse(false)) callback.accept(key);
        });
    }

    @Override
    public boolean columnExists(ChunkPos pos) throws IOException {
        return this.save.getSaveSection2D().hasEntry(new EntryLocation2D(pos.x, pos.z));
    }

    @Override
    public boolean cubeExists(CubePos pos) throws IOException {
        return this.save.getSaveSection3D().hasEntry(new EntryLocation3D(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    public NBTTagCompound readColumn(ChunkPos pos) throws IOException {
        //we use a true here in order to force creation and caching of the new region, thus avoiding an expensive Files.exists() check for every cube/column (which
        // is really expensive on windows)
        Optional<ByteBuffer> data = this.save.load(new EntryLocation2D(pos.x, pos.z), true);
        return data.isPresent()
                ? CompressedStreamTools.readCompressed(new ByteArrayInputStream(data.get().array())) //decompress and parse NBT
                : null; //column doesn't exist
    }

    @Override
    public NBTTagCompound readCube(CubePos pos) throws IOException {
        //see comment in readColumn
        Optional<ByteBuffer> data = this.save.load(new EntryLocation3D(pos.getX(), pos.getY(), pos.getZ()), true);
        return data.isPresent()
                ? CompressedStreamTools.readCompressed(new ByteArrayInputStream(data.get().array())) //decompress and parse NBT
                : null; //cube doesn't exist
    }

    @Override
    public void writeColumn(ChunkPos pos, NBTTagCompound nbt) throws IOException {
        ByteBuf compressedBuf = compressNBT(nbt);
        try {
            //write compressed data to disk
            this.save.save2d(new EntryLocation2D(pos.x, pos.z), compressedBuf.nioBuffer());
        } finally {
            compressedBuf.release();
        }
    }

    @Override
    public void writeCube(CubePos pos, NBTTagCompound nbt) throws IOException {
        ByteBuf compressedBuf = compressNBT(nbt);
        try {
            //write compressed data to disk
            this.save.save3d(new EntryLocation3D(pos.getX(), pos.getY(), pos.getZ()), compressedBuf.nioBuffer());
        } finally {
            compressedBuf.release();
        }
    }

    @Override
    public void writeBatch(NBTBatch batch) throws IOException {
        Map<EntryLocation2D, ByteBuf> compressedColumns = Collections.emptyMap();
        Map<EntryLocation3D, ByteBuf> compressedCubes = Collections.emptyMap();
        try {
            //compress NBT data
            compressedColumns = this.compressNBTForBatchWrite(batch.columns, pos -> new EntryLocation2D(pos.x, pos.z));
            compressedCubes = this.compressNBTForBatchWrite(batch.cubes, pos -> new EntryLocation3D(pos.getX(), pos.getY(), pos.getZ()));

            //write compressed data to disk
            if (!compressedColumns.isEmpty()) {
                this.save.save2d(compressedColumns.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().nioBuffer())));
            }
            if (!compressedCubes.isEmpty()) {
                this.save.save3d(compressedCubes.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().nioBuffer())));
            }
            CubicChunks.LOGGER.debug("Saved batch: {} columns and {} cubes", batch.columns.size(), batch.cubes.size());
        } finally {
            compressedColumns.values().forEach(ByteBuf::release);
            compressedCubes.values().forEach(ByteBuf::release);
        }
    }

    protected <KI, KO> Map<KO, ByteBuf> compressNBTForBatchWrite(Map<KI, NBTTagCompound> nbt, Function<KI, KO> keyMappingFunction) throws IOException {
        if (nbt.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<KO, ByteBuf> compressed = new LinkedHashMap<>();
        try {
            // The per-world writer already bounds batches. A single compression owner avoids
            // unbounded common-pool competition and gives every native buffer a clear lifetime.
            for (Map.Entry<KI, NBTTagCompound> entry : nbt.entrySet()) {
                KO key = keyMappingFunction.apply(entry.getKey());
                if (compressed.containsKey(key)) throw new IllegalArgumentException("Duplicate mapped storage position");
                compressed.put(key, compressNBT(entry.getValue()));
            }
            return compressed;
        } catch (IOException | RuntimeException | Error e) {
            compressed.values().forEach(ByteBuf::release);
            throw e;
        }
    }

    /** Transfers one buffer reference to the caller on success, releases it on failure. */
    protected ByteBuf compressNBT(NBTTagCompound nbt) throws IOException {
        ByteBuf compressed = UnpooledByteBufAllocator.DEFAULT.ioBuffer();
        boolean success = false;
        try {
            CompressedStreamTools.writeCompressed(nbt, new ByteBufOutputStream(compressed));
            success = true;
            return compressed;
        } finally {
            if (!success) compressed.release();
        }
    }

    @Override
    public void forEachColumn(Consumer<ChunkPos> callback) throws IOException {
        forEach(columns, overflowColumns, pos -> callback.accept(new ChunkPos(pos.getEntryX(), pos.getEntryZ())));
    }

    @Override
    public void forEachCube(Consumer<CubePos> callback) throws IOException {
        forEach(cubes, overflowCubes, pos -> callback.accept(new CubePos(pos.getEntryX(), pos.getEntryY(), pos.getEntryZ())));
    }

    @Override
    public void flush() throws IOException {
        regions.flush();
    }

    @Override
    public void close() throws IOException {
        regions.close();
    }
}
