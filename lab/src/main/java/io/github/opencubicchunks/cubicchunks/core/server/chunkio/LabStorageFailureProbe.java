package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Faults are confined to a new lab-owned storage directory, separate from the world's region files. */
public final class LabStorageFailureProbe implements AutoCloseable {
    private final FaultStore storage;
    private final PendingCubeWrites writes;
    private final CubePos cube;
    private final ChunkPos column;
    private final NBTTagCompound expectedCube, expectedColumn;
    private boolean complete;

    public LabStorageFailureProbe(Path path, Cube source) throws IOException {
        storage = new FaultStore(path);
        cube = source.getCoords(); column = source.getColumn().getPos();
        expectedCube = IONbtWriter.write(source); expectedColumn = IONbtWriter.write(source.getColumn());
        expectedCube.setInteger("LabStorageRevision", 1); expectedColumn.setInteger("LabStorageRevision", 1);
        writes = new PendingCubeWrites(storage, 16, 64, error -> { });
        writes.putCube(cube, expectedCube); writes.putColumn(column, expectedColumn); writes.signal();
    }

    public boolean observe() throws IOException {
        if (!writes.hasFailure()) return false;
        if (storage.attempts.get() != 1) throw new IllegalStateException("Failed worker retried automatically");
        NBTTagCompound exposed = writes.readCube(cube);
        exposed.setInteger("LabStorageRevision", -1);
        if (writes.readCube(cube).getInteger("LabStorageRevision") != 1) throw new IllegalStateException("Pending read aliases snapshot");
        try { writes.flush(); throw new IllegalStateException("Persistent failure was not reported by flush"); }
        catch (IOException expected) { }
        if (storage.attempts.get() != 2 || !writes.hasPending()) throw new IllegalStateException("Failed flush discarded snapshots");
        NBTTagCompound latestCube = expectedCube.copy(), latestColumn = expectedColumn.copy();
        latestCube.setInteger("LabStorageRevision", 2); latestColumn.setInteger("LabStorageRevision", 2);
        writes.putCube(cube, latestCube); writes.putColumn(column, latestColumn);
        storage.fail = false; writes.flush();
        if (writes.hasPending() || writes.hasFailure() || !latestCube.equals(storage.readCube(cube))
                || !latestColumn.equals(storage.readColumn(column))) throw new IllegalStateException("Recovery did not persist latest snapshots");
        complete = true;
        return true;
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", complete); result.put("injectedWriteFailures", 2);
        result.put("writeAttempts", storage.attempts.get()); result.put("persistedRevision", complete ? 2 : null);
        result.put("boundary", "Actual writer and RegionLib storage with synthetic pre-write IOExceptions; separate lab directory, no power-loss or physical disk-fault test.");
        return result;
    }

    @Override public void close() throws IOException { writes.stop(); storage.close(); }

    private static final class FaultStore extends RegionCubeStorage {
        final AtomicInteger attempts = new AtomicInteger(); volatile boolean fail = true;
        FaultStore(Path path) throws IOException { super(path); }
        @Override public void writeBatch(NBTBatch batch) throws IOException {
            attempts.incrementAndGet();
            if (fail) throw new IOException("Lab-injected storage write failure");
            super.writeBatch(batch);
        }
    }
}
