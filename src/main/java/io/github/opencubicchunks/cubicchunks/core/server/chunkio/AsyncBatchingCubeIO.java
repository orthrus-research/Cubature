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

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage;
import io.github.opencubicchunks.cubicchunks.api.world.storage.IRetryableCloseStorage;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge.AsyncWorldIOExecutor;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.datafix.FixTypes;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementation of {@link ICubeIO} which internally batches cubes/columns together, and forwards them along to a {@link ICubicStorage} on the I/O thread.
 *
 * @author DaPorkchop_
 */
public class AsyncBatchingCubeIO implements ICubeIO {
    protected final ReadWriteLock lock = new ReentrantReadWriteLock();

    protected final World world;
    protected final ICubicStorage storage;

    protected final Map<ChunkPos, NBTTagCompound> pendingColumns;
    protected final Map<CubePos, NBTTagCompound> pendingCubes;
    private final PendingCubeWrites writes;
    private final StorageCloseState closeState;

    /** Compatibility view for existing subclasses; release ownership is tracked by closeState. */
    @Deprecated
    protected volatile boolean open = true;

    public AsyncBatchingCubeIO(World world, ICubicStorage storage) throws IOException {
        this.world = Objects.requireNonNull(world, "world");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.writes = new PendingCubeWrites(storage, Math.max(1, CubicChunksConfig.storageBatchColumns),
                Math.max(1, CubicChunksConfig.storageBatchCubes), error ->
                CubicChunks.LOGGER.error("Cube storage write failed; snapshots retained. Explicit flush will retry.", error));
        this.pendingColumns = writes.columns;
        this.pendingCubes = writes.cubes;
        this.closeState = new StorageCloseState(storage::close, writes::stop,
                storage instanceof IRetryableCloseStorage);
    }

    protected void ensureOpen() {
        if (!this.open) {
            throw new IllegalStateException("Cube storage is no longer open", this.closeState.failure());
        }
        this.closeState.requireOpen();
    }

    public ICubicStorage getStorage() {
        return this.storage;
    }

    @Override
    public boolean columnExists(int columnX, int columnZ) {
        ChunkPos pos = new ChunkPos(columnX, columnZ);

        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            return this.pendingColumns.containsKey(pos) || this.storage.columnExists(pos);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot check saved column " + pos, e);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public boolean cubeExists(int cubeX, int cubeY, int cubeZ) {
        CubePos pos = new CubePos(cubeX, cubeY, cubeZ);

        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            return this.pendingCubes.containsKey(pos) || this.storage.cubeExists(pos);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot check saved cube " + pos, e);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public PartialData<Chunk> loadColumnNbt(int chunkX, int chunkZ) throws IOException {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);

        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            NBTTagCompound nbt = this.writes.readColumn(pos);
            if (nbt == null) { //column isn't cached, forward request on to storage
                nbt = this.storage.readColumn(pos);
            }
            if (nbt != null) { //fix column data
                nbt = FMLCommonHandler.instance().getDataFixer().process(FixTypes.CHUNK, nbt);
            }
            return new PartialData<>(null, nbt);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public PartialData<ICube> loadCubeNbt(Chunk column, int cubeY) throws IOException {
        CubePos pos = new CubePos(column.x, cubeY, column.z);

        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            NBTTagCompound nbt = this.writes.readCube(pos);
            if (nbt == null) { //cube isn't cached, forward request on to storage
                nbt = this.storage.readCube(pos);
            }
            return new PartialData<>(null, nbt);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void saveColumn(Chunk column) {
        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            // NOTE: this function blocks the world thread
            // make it as fast as possible by offloading processing to the IO thread
            // except we have to write the NBT in this thread to avoid problems
            // with concurrent access to world data structures

            // add the column to the save queue
            this.writes.putColumn(column.getPos(), IONbtWriter.write(column));
            column.setModified(false);

            // signal the IO thread to process the save queue
            this.writes.signal();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void saveCube(Cube cube) {
        // make sure all light updates are processed, even if someone calls this from the outside
        ((ICubicWorldInternal) world).getLightingManager().processUpdates();

        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            // NOTE: this function blocks the world thread, so make it fast

            this.writes.putCube(cube.getCoords(), IONbtWriter.write(cube));
            cube.markSaved();

            // signal the IO thread to process the save queue
            this.writes.signal();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public int getPendingColumnCount() {
        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            return this.pendingColumns.size();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public int getPendingCubeCount() {
        this.lock.readLock().lock();
        try {
            this.ensureOpen();

            return this.pendingCubes.size();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void flush() throws IOException { //only used by "/save-all flush" command
        PendingCubeWrites.checkInterrupted();
        lockWriteInterruptibly();
        try {
            this.ensureOpen();

            this.writes.flush();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        if (this.closeState.state() == StorageCloseState.State.CLOSED) {
            return;
        }
        PendingCubeWrites.checkInterrupted();
        // Quiesce this world's readers before taking the backend write lock. A timeout or
        // interruption leaves storage/writer open for recovery; other worlds keep their work.
        if (this.closeState.state() == StorageCloseState.State.OPEN) {
            AsyncWorldIOExecutor.shutdownWorldBlocking(this.world);
        }

        lockWriteInterruptibly();
        try {
            if (this.closeState.state() == StorageCloseState.State.OPEN) {
                this.writes.flush();
            }
            // Failed backend release never reopens admission or flushes partially closed handles.
            // Only providers with the explicit retry contract may repeat their backend close.
            this.open = false;
            this.closeState.close();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public boolean canAutoSave() {
        return this.open && this.closeState.state() == StorageCloseState.State.OPEN && !this.writes.hasFailure()
                && this.pendingColumns.size() + this.pendingCubes.size()
                < Math.max(1, CubicChunksConfig.autosavePendingThreshold);
    }

    @Override
    public boolean writeNextIO() {
        return this.open && this.closeState.state() == StorageCloseState.State.OPEN && this.writes.writeNext();
    }

    private void lockWriteInterruptibly() throws InterruptedIOException {
        try {
            this.lock.writeLock().lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException error = new InterruptedIOException("Interrupted waiting for cube storage barrier");
            error.initCause(e);
            throw error;
        }
    }

    // parsing methods
    // these methods don't use the instance state for anything meaningful, so we can just let them do their thing without worrying about locks and whatnot

    @Override
    public void loadColumnAsyncPart(PartialData<Chunk> info, int chunkX, int chunkZ) {
        if (info.getNbt() == null) {
            return;
        }
        Chunk chunk = IONbtReader.readColumn(this.world, chunkX, chunkZ, info.getNbt());
        info.setObject(chunk);
    }

    @Override
    public void loadColumnSyncPart(PartialData<Chunk> info) {
        //no-op
    }

    @Override
    public void loadCubeAsyncPart(PartialData<ICube> info, Chunk column, int cubeY) {
        if (info.getNbt() == null) {
            return;
        }

        // restore the cube - async part
        Cube cube = IONbtReader.readCubeAsyncPart(column, column.x, cubeY, column.z, info.getNbt());
        info.setObject(cube);
    }

    @Override
    public void loadCubeSyncPart(PartialData<ICube> info) {
        IONbtReader.readCubeSyncPart((Cube) info.object, this.world, info.nbt);
    }
}
