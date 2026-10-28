package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge.AsyncWorldIOExecutor;
import io.github.opencubicchunks.cubicchunks.core.util.ticket.ITicket;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded native correctness workload. Audits, forced setup and durable barriers are not performance samples. */
public final class LabMaintenanceProbe implements AutoCloseable {
    private static final int Z = 736, Y = 5, COHORT_COLUMNS = 4, LEVELS = 4;
    private static final int TICKET_X = 752, FORGE_X = 768, PENDING_X = 784, ADDED_X = 800;
    private static final int ADDED_CUBES = 16, MAX_STEPS = 2400, MAX_AUDIT_ENTRIES = 131072;
    private static final String[] BLOCK_ARRAYS = {"Blocks", "Data", "Add", "Add2"};
    private static final ForgeChunkManager.LoadingCallback LAB_TICKETS = (tickets, world) -> { };

    private final CubeProviderServer provider;
    private final ITicket hold = () -> false;
    private final Map<CubePos, Cube> held = new LinkedHashMap<>();
    private final Map<CubePos, String> expectedDigests = new LinkedHashMap<>();
    private final List<Map<String, Object>> trace = new ArrayList<>();
    private final ChunkGc collector;
    private ForgeChunkManager.Ticket forgeTicket;
    private Cube ticketed, forged;
    private Chunk pendingColumn;
    private Consumer<Cube> pendingSubscription;
    private Thread owner;
    private int phase, steps, added, pendingCallbacks, auditedCalls, totalRemoved, maximumRemoved, oldestSavedAt = -1;
    private int checks, reloads, initialLoaded, evictionCompletedAt;
    private long preparationNanos, collectorNanos, auditNanos, flushNanos, reloadNanos;
    private boolean prepared, closed;

    public LabMaintenanceProbe(CubeProviderServer provider) {
        this.provider = provider;
        this.collector = new ChunkGc(provider);
    }

    private int effectiveUnloadQuota() {
        try {
            return ((Number)provider.getClass().getMethod("getMaintenanceUnloadLimit").invoke(provider)).intValue();
        } catch (NoSuchMethodException historicalArtifact) {
            return CubicChunksConfig.maintenanceUnloadsPerTick;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot read effective maintenance quota", error);
        }
    }

    public void prepare() throws IOException {
        owner = Thread.currentThread();
        long start = System.nanoTime();
        try {
            require(provider.usesIncrementalMaintenance(), "maintenance-contracts requires incrementalMaintenance enabled at world construction");
            require(CubicChunksConfig.maintenanceUnloadsPerTick > 0 && CubicChunksConfig.maintenanceInspectionsPerTick > 0,
                    "maintenance allowances must make progress");
            require(CubicChunksConfig.chunkGCInterval >= 0 && CubicChunksConfig.chunkGCInterval <= 2000,
                    "fixture supports a bounded GC interval of 0..2000 calls");
            for (int column = 0; column < COHORT_COLUMNS; column++)
                require(provider.getLoadedColumn(736 + column * 2, Z) == null, "maintenance fixture column was already resident");
            for (int column = 0; column < COHORT_COLUMNS; column++) {
                int x = 736 + column * 2;
                for (int y = Y; y < Y + LEVELS; y++) {
                    Cube cube = create(x, y);
                    expectedDigests.put(cube.getCoords(), null);
                    retain(cube);
                }
            }
            ticketed = create(TICKET_X, Y); retain(ticketed);
            forged = create(FORGE_X, Y);
            installForgeTicket();
            Cube pending = create(PENDING_X, Y); retain(pending);
            pendingColumn = (Chunk) pending.getColumn();
            for (Cube cube : held.values()) provider.getCubeIO().saveCube(cube);
            provider.getCubeIO().saveCube(forged);
            for (int x : new int[]{736, 738, 740, 742, TICKET_X, FORGE_X, PENDING_X}) {
                Chunk column = provider.getLoadedColumn(x, Z);
                require(column != null, "fixture parent column missing before save");
                provider.getCubeIO().saveColumn(column);
            }
            flush();
            for (CubePos position : expectedDigests.keySet()) expectedDigests.put(position, blockDigest(read(position)));

            // Establish the periodic trigger while the known cohort is protected. These are setup calls,
            // not simulated game ticks; the audited trigger below still goes through ChunkGc.tick().
            for (int i = 0; i < CubicChunksConfig.chunkGCInterval; i++) collector.tick();
            for (CubePos position : expectedDigests.keySet()) release(position);
            release(pending.getCoords());
            for (Iterator<Cube> it = provider.cubesIterator(); it.hasNext();) {
                Cube cube = it.next();
                if (cube.getX() == PENDING_X && cube.getZ() == Z) {
                    require(provider.tryUnloadCube(cube), "pending-I/O setup cube was unexpectedly retained");
                    it.remove();
                }
            }
            flush();
            require(provider.getLoadedColumn(PENDING_X, Z) == pendingColumn, "pending-I/O parent disappeared during setup");
            pendingSubscription = cube -> {
                pendingSubscription = null;
                require(Thread.currentThread() == owner, "pending-I/O callback left the owner thread");
                require(cube != null && cube.getX() == PENDING_X && cube.getY() == Y && cube.getZ() == Z,
                        "pending-I/O reload returned absent or unrelated cube");
                checkMarker(cube, Blocks.GOLD_BLOCK);
                require(++pendingCallbacks == 1, "pending-I/O callback was duplicated");
                retain(cube);
            };
            provider.asyncGetCube(PENDING_X, Y, Z, Requirement.LOAD, pendingSubscription);
            require(!AsyncWorldIOExecutor.canDropColumn(provider.world, PENDING_X, Z), "queued cube did not pin its empty parent");
            require(!provider.tryUnloadColumn(pendingColumn), "pending-I/O parent passed unload guard");
            require(!provider.tryUnloadCube(ticketed), "ordinary lab ticket passed unload guard");
            require(!provider.tryUnloadCube(forged), "Forge persistent-column ticket passed unload guard");
            initialLoaded = provider.getLoadedCubeCount();
            auditCollector();
            // This check is deliberately in the same owner turn as submission. Completion may
            // legally release the pin at the next ordinary owner service opportunity.
            require(provider.getLoadedColumn(PENDING_X, Z) == pendingColumn
                    && !AsyncWorldIOExecutor.canDropColumn(provider.world, PENDING_X, Z),
                    "same-turn maintenance removed or unpinned an in-flight empty parent");
            prepared = true;
        } catch (IOException | RuntimeException | Error error) {
            close();
            throw error;
        } finally {
            preparationNanos = System.nanoTime() - start;
        }
    }

    public boolean tick() throws IOException {
        require(prepared && !closed && Thread.currentThread() == owner, "maintenance fixture is not active on its owner");
        require(++steps <= MAX_STEPS, "bounded maintenance fixture did not complete");
        auditCollector();
        if (phase == 0) {
            if (!cohortEvicted() || pendingCallbacks != 1) return false;
            evictionCompletedAt = steps;
            ticketed.setBlockState(marker(ticketed.getCoords()), Blocks.DIAMOND_BLOCK.getDefaultState());
            ticketed.markDirty();
            AutosaveScope.run(() -> provider.saveChunks(true));
            phase = 1;
        } else if (phase == 1) {
            if (added < ADDED_CUBES) {
                Cube cube = create(ADDED_X + added++ * 4, Y);
                retain(cube);
                // New dirty retained entries arrive while the older ticketed cube's discovery
                // lane is already in flight. The fixture stops adding after sixteen entries.
                AutosaveScope.run(() -> provider.saveChunks(true));
            }
            // Cube NBT uses the state palette encoding; Block.getStateId uses a different legacy encoding.
            if (oldestSavedAt < 0 && savedMarkerPaletteId(read(ticketed.getCoords())) == Block.BLOCK_STATE_IDS.get(Blocks.DIAMOND_BLOCK.getDefaultState()))
                oldestSavedAt = steps;
            if (added != ADDED_CUBES || oldestSavedAt < 0 || provider.getPendingAutosaveDiscoveryCount() != 0
                    || provider.getPendingAutosaveCount() != 0) return false;
            flush();
            require(savedMarkerPaletteId(read(ticketed.getCoords())) == Block.BLOCK_STATE_IDS.get(Blocks.DIAMOND_BLOCK.getDefaultState()),
                    "old dirty cube revision did not survive durable flush");
            verifyReload();
            phase = 2;
            return true;
        }
        return false;
    }

    private Cube create(int x, int y) {
        Cube cube = provider.getCubeNow(x, y, Z, Requirement.LIGHT);
        require(cube != null && cube.getX() == x && cube.getY() == y && cube.getZ() == Z,
                "fixture generation returned absent or unrelated cube");
        require(cube.isFullyPopulated() && cube.isInitialLightingDone() && cube.isSurfaceTracked(), "fixture cube is incomplete");
        cube.setBlockState(marker(cube.getCoords()), Blocks.GOLD_BLOCK.getDefaultState());
        cube.markDirty();
        return cube;
    }

    private void installForgeTicket() {
        ChunkPos position = new ChunkPos(FORGE_X, Z);
        require(!ForgeChunkManager.getPersistentChunksFor(provider.world).containsKey(position), "Forge sentinel already has an unrelated persistent ticket");
        ModContainer container = Loader.instance().getIndexedModList().get("cubiclab");
        require(container != null && container.getMod() != null, "isolated lab mod container is unavailable");
        Object labMod = container.getMod();
        ForgeChunkManager.setForcedChunkLoadingCallback(labMod, LAB_TICKETS);
        forgeTicket = ForgeChunkManager.requestTicket(labMod, provider.world, ForgeChunkManager.Type.NORMAL);
        require(forgeTicket != null, "Forge refused the lab-owned persistent ticket");
        ForgeChunkManager.forceChunk(forgeTicket, position);
        require(ForgeChunkManager.getPersistentChunksFor(provider.world).get(position).contains(forgeTicket), "Forge ticket was not attached to its sentinel");
    }

    private void auditCollector() {
        long start = System.nanoTime();
        List<Cube> cubes = new ArrayList<>();
        List<Chunk> columns = new ArrayList<>();
        for (Iterator<Cube> it = provider.cubesIterator(); it.hasNext();) {
            cubes.add(it.next());
            require(cubes.size() <= MAX_AUDIT_ENTRIES, "native audit exceeds its cube bound");
        }
        for (Iterator<Chunk> it = provider.columnsIterator(); it.hasNext();) {
            columns.add(it.next());
            require(cubes.size() + columns.size() <= MAX_AUDIT_ENTRIES, "native audit exceeds its total identity bound");
        }
        long before = System.nanoTime();
        collector.tick();
        long cost = System.nanoTime() - before;
        collectorNanos += cost;
        int removedCubes = 0, removedColumns = 0;
        for (Cube cube : cubes) if (provider.getLoadedCube(cube.getX(), cube.getY(), cube.getZ()) != cube) removedCubes++;
        for (Chunk column : columns) if (provider.getLoadedColumn(column.x, column.z) != column) removedColumns++;
        int removed = removedCubes + removedColumns;
        require(removed <= effectiveUnloadQuota(), "one periodic maintenance call exceeded the actual unload quota");
        require(provider.getLoadedCube(TICKET_X, Y, Z) == ticketed && ticketed.getTickets().contains(hold), "lab-ticketed sentinel was removed");
        require(provider.getLoadedCube(FORGE_X, Y, Z) == forged
                && ForgeChunkManager.getPersistentChunksFor(provider.world).get(new ChunkPos(FORGE_X, Z)).contains(forgeTicket),
                "Forge-ticketed sentinel was removed");
        totalRemoved += removed;
        maximumRemoved = Math.max(maximumRemoved, removed);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("call", ++auditedCalls); row.put("fixtureStep", steps); row.put("collectorNanos", cost);
        row.put("removedCubes", removedCubes); row.put("removedColumns", removedColumns);
        row.put("loadedCubes", provider.getLoadedCubeCount()); row.put("pendingDiscovery", provider.getPendingAutosaveDiscoveryCount());
        row.put("pendingSnapshots", provider.getPendingAutosaveCount());
        trace.add(row);
        auditNanos += System.nanoTime() - start;
    }

    private boolean cohortEvicted() {
        for (CubePos position : expectedDigests.keySet())
            if (provider.getLoadedCube(position.getX(), position.getY(), position.getZ()) != null) return false;
        for (int i = 0; i < COHORT_COLUMNS; i++) if (provider.getLoadedColumn(736 + i * 2, Z) != null) return false;
        return true;
    }

    private void verifyReload() throws IOException {
        long start = System.nanoTime();
        for (Map.Entry<CubePos, String> entry : expectedDigests.entrySet()) {
            CubePos position = entry.getKey();
            require(entry.getValue().equals(blockDigest(read(position))), "saved block arrays changed during incremental maintenance at " + position);
            Cube cube = provider.getCubeNow(position.getX(), position.getY(), position.getZ(), Requirement.LOAD);
            require(cube != null && cube.getCoords().equals(position), "evicted cohort cube was not reloadable");
            checkMarker(cube, Blocks.GOLD_BLOCK);
            require(cube.isFullyPopulated() && cube.isInitialLightingDone() && cube.isSurfaceTracked(), "reloaded cohort lost complete flags");
            retain(cube); reloads++;
        }
        reloadNanos += System.nanoTime() - start;
    }

    private NBTTagCompound read(CubePos position) throws IOException {
        Chunk column = provider.getColumn(position.getX(), position.getZ(), Requirement.LOAD);
        require(column != null && !column.isEmpty(), "saved fixture parent is unavailable");
        NBTTagCompound nbt = provider.getCubeIO().loadCubeNbt(column, position.getY()).getNbt();
        require(nbt != null, "saved fixture cube is unavailable");
        return nbt.getCompoundTag("Level");
    }

    private void flush() throws IOException {
        long start = System.nanoTime();
        try { provider.getCubeIO().flush(); }
        finally { flushNanos += System.nanoTime() - start; }
    }

    private static String blockDigest(NBTTagCompound level) {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
        for (String coordinate : new String[]{"x", "y", "z"}) updateInt(digest, level.getInteger(coordinate));
        NBTTagList source = level.getTagList("Sections", 10);
        List<NBTTagCompound> sections = new ArrayList<>();
        for (int i = 0; i < source.tagCount(); i++) sections.add(source.getCompoundTagAt(i));
        sections.sort(Comparator.comparingInt(section -> section.getByte("Y")));
        updateInt(digest, sections.size());
        for (NBTTagCompound section : sections) {
            updateInt(digest, section.getByte("Y"));
            for (String field : BLOCK_ARRAYS) {
                digest.update(field.getBytes(StandardCharsets.US_ASCII));
                byte[] bytes = section.getByteArray(field);
                updateInt(digest, bytes.length); digest.update(bytes);
            }
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24)); digest.update((byte) (value >>> 16)); digest.update((byte) (value >>> 8)); digest.update((byte) value);
    }

    static int savedMarkerPaletteId(NBTTagCompound level) {
        NBTTagList sections = level.getTagList("Sections", 10);
        if (sections.tagCount() != 1) throw new IllegalStateException("Marker requires one saved cube section");
        NBTTagCompound section = sections.getCompoundTagAt(0);
        int index = 1 << 8 | 1 << 4 | 1;
        byte[] blocks = section.getByteArray("Blocks"), data = section.getByteArray("Data");
        if (blocks.length != 4096 || data.length != 2048) throw new IllegalStateException("Malformed saved marker section");
        return ((blocks[index] & 255) << 4) | nibble(data, index) | (nibble(section.getByteArray("Add"), index) << 12)
                | (nibble(section.getByteArray("Add2"), index) << 16);
    }

    private static int nibble(byte[] data, int index) {
        if (data.length == 0) return 0;
        if (data.length != 2048) throw new IllegalStateException("Malformed saved marker nibble array");
        return (data[index >>> 1] >>> ((index & 1) << 2)) & 15;
    }

    private static BlockPos marker(CubePos position) {
        return new BlockPos((position.getX() << 4) + 1, (position.getY() << 4) + 1, (position.getZ() << 4) + 1);
    }

    private void checkMarker(Cube cube, Block expected) {
        require(cube.getBlockState(marker(cube.getCoords())).getBlock() == expected, "fixture marker was lost");
    }

    private void retain(Cube cube) { cube.getTickets().add(hold); held.put(cube.getCoords(), cube); }
    private void release(CubePos position) { Cube cube = held.remove(position); if (cube != null) cube.getTickets().remove(hold); }
    private void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); checks++; }

    public int operations() { return auditedCalls + reloads; }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", phase == 2); result.put("checks", checks); result.put("auditedCalls", auditedCalls);
        result.put("steps", steps); result.put("evictionCompletedAtStep", evictionCompletedAt); result.put("oldestSavedAtStep", oldestSavedAt);
        result.put("initialLoadedCubes", initialLoaded); result.put("cohortCubes", expectedDigests.size()); result.put("reloadedCubes", reloads);
        result.put("totalRemovedIdentities", totalRemoved); result.put("maximumRemovedPerCall", maximumRemoved);
        result.put("unloadQuota", effectiveUnloadQuota()); result.put("pendingIoCallbacks", pendingCallbacks);
        result.put("addedDirtyRetainedCubes", added); result.put("preparationNanos", preparationNanos); result.put("collectorNanos", collectorNanos);
        result.put("auditNanos", auditNanos); result.put("blockingFlushNanos", flushNanos); result.put("reloadNanos", reloadNanos);
        List<Map<String, Object>> witnesses = new ArrayList<>();
        expectedDigests.forEach((position, digest) -> {
            Map<String, Object> witness = new LinkedHashMap<>();
            witness.put("cube", Arrays.asList(position.getX(), position.getY(), position.getZ()));
            witness.put("markerBlock", "minecraft:gold_block"); witness.put("markerLocal", Arrays.asList(1, 1, 1));
            witness.put("blockArraySha256", digest); witnesses.add(witness);
        });
        result.put("savedWitnesses", witnesses); result.put("trace", trace);
        result.put("digestFormat", "SHA-256: big-endian Level x,y,z then section count; sections sorted signed Y; each Y int then ASCII Blocks/Data/Add/Add2 name + int byte length + raw bytes. Excludes lighting, entities, biomes and compression.");
        result.put("scope", "candidate-only native maintenance contracts; direct periodic collector identity audit and native NBT/provider reload. "
                + "Synthetic owned markers/tickets, setup calls and flushes are diagnostic costs. Saved witnesses require separate stopped-world inspection; no ordinary throughput claim.");
        return result;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            if (pendingSubscription != null) {
                AsyncWorldIOExecutor.dropQueuedCubeLoad(provider.world, PENDING_X, Y, Z, pendingSubscription);
                pendingSubscription = null;
            }
        } finally {
            try {
                if (forgeTicket != null) {
                    ForgeChunkManager.Ticket ticket = forgeTicket;
                    forgeTicket = null;
                    try { ForgeChunkManager.unforceChunk(ticket, new ChunkPos(FORGE_X, Z)); }
                    finally { ForgeChunkManager.releaseTicket(ticket); }
                }
            } finally {
                for (Cube cube : held.values()) cube.getTickets().remove(hold);
                held.clear();
            }
        }
    }
}
