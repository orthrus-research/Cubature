package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;

/**
 * The provider's periodic autosave, paced over its ticks. The periodic save selects what is dirty: a
 * scan of everything loaded, or with incremental maintenance a request to the discovery cursors, which
 * also discover continuously from deliveryMode SAVE. Each provider tick snapshots selected cubes and
 * columns within the autosave budget (autosaveTimeMillis, autosaveSnapshotsPerTick), alternating which
 * goes first, while the writer accepts them (ICubeIO.canAutoSave).
 */
final class PeriodicAutosave {
    private final CubeProviderServer provider;
    /** The provider's incremental maintenance, or null without it. */
    @Nullable private final MaintenanceState maintenance;
    private final PacedAutosave<CubePos> autosaveCubes = new PacedAutosave<>();
    private final PacedAutosave<ChunkPos> autosaveColumns = new PacedAutosave<>();
    private int lastAutosaveProcessed;
    private boolean autosaveColumnsFirst;

    PeriodicAutosave(CubeProviderServer provider, @Nullable MaintenanceState maintenance) {
        this.provider = provider;
        this.maintenance = maintenance;
    }

    /** The periodic save: selects what is dirty now among the provider's loaded cubes and columns. */
    void begin(Iterable<Cube> cubes, Iterable<Chunk> columns) {
        ((ICubicWorldInternal) provider.world).getLightingManager().processUpdates();
        if (maintenance != null) {
            maintenance.discoverCubes = Math.max(maintenance.discoverCubes, maintenance.cubes.size());
            maintenance.discoverColumns = Math.max(maintenance.discoverColumns, maintenance.columns.size());
            return;
        }
        for (Cube cube : cubes) if (cube.needsSaving()) autosaveCubes.add(cube.getCoords());
        for (Chunk chunk : columns) if (chunk.needsSaving(true)) autosaveColumns.add(chunk.getPos());
        // Continuations run in provider ticks. The initial scan does not serialize dirty cubes.
    }

    /** Drops every selection: a full save has just written everything dirty. */
    void clear() {
        autosaveCubes.clear();
        autosaveColumns.clear();
        if (maintenance != null) maintenance.discoverCubes = maintenance.discoverColumns = 0;
    }

    /** One provider tick of snapshots within the autosave budget. */
    void tick() {
        lastAutosaveProcessed = 0;
        if (provider.world.disableLevelSaving) return;
        long start = System.nanoTime();
        long budget = Math.max(1, CubicChunksConfig.autosaveTimeMillis) * 1_000_000L;
        int limit = Math.max(1, CubicChunksConfig.autosaveSnapshotsPerTick);
        if (maintenance != null && maintenance.continuousDiscovery
                && maintenance.discoverCubes == 0 && maintenance.discoverColumns == 0) {
            // The existing two independent bounded cursors discover dirty state continuously.
            // Snapshot publication still runs on this owner thread and honors writer backpressure.
            maintenance.discoverCubes = maintenance.cubes.size();
            maintenance.discoverColumns = maintenance.columns.size();
        }
        discoverAutosave(start, budget / 2);
        // Alternate priority, including with a quota of one or an over-budget first snapshot.
        autosaveColumnsFirst = !autosaveColumnsFirst;
        long remaining = budget - (System.nanoTime() - start);
        if (autosaveColumnsFirst) lastAutosaveProcessed = tickAutosaveColumns(Math.min(4, limit), Math.max(1, remaining));
        remaining = budget - (System.nanoTime() - start);
        if (remaining > 0 && lastAutosaveProcessed < limit) lastAutosaveProcessed += autosaveCubes.tick(limit - lastAutosaveProcessed, remaining, provider.getCubeIO()::canAutoSave, pos -> {
            Cube cube = provider.getLoadedCube(pos);
            if (cube != null && cube.needsSaving()) provider.getCubeIO().saveCube(cube);
        }, System::nanoTime);
        remaining = budget - (System.nanoTime() - start);
        if (!autosaveColumnsFirst && remaining > 0 && lastAutosaveProcessed < limit)
            lastAutosaveProcessed += tickAutosaveColumns(Math.min(4, limit - lastAutosaveProcessed), remaining);
    }

    private int tickAutosaveColumns(int limit, long budget) {
        return autosaveColumns.tick(limit, budget, provider.getCubeIO()::canAutoSave, pos -> {
            Chunk column = provider.getLoadedColumn(pos.x, pos.z);
            if (column != null && column.needsSaving(true)) provider.getCubeIO().saveColumn(column);
        }, System::nanoTime);
    }

    /** Pending owner-thread snapshots, distinct from the writer's serialized-NBT backlog. */
    int pending() { return autosaveCubes.size() + autosaveColumns.size(); }

    int lastProcessed() { return lastAutosaveProcessed; }

    int pendingDiscovery() {
        return maintenance == null ? 0 : maintenance.discoverCubes + maintenance.discoverColumns;
    }

    private void discoverAutosave(long start, long budget) {
        if (maintenance == null) return;
        for (int inspected = 0; inspected < CubicChunksConfig.maintenanceInspectionsPerTick
                && (maintenance.discoverCubes > 0 || maintenance.discoverColumns > 0)
                && pending() < CubicChunksConfig.autosavePendingThreshold
                && (inspected == 0 || System.nanoTime() - start < budget); inspected++) {
            maintenance.discoveryColumnFirst = !maintenance.discoveryColumnFirst;
            if (maintenance.discoverColumns > 0 && (maintenance.discoveryColumnFirst || maintenance.discoverCubes == 0)) {
                maintenance.discoverColumns--;
                Chunk column = maintenance.columns.next(true);
                if (column == null) maintenance.discoverColumns = 0;
                else if (column.needsSaving(true)) autosaveColumns.add(column.getPos());
            } else {
                maintenance.discoverCubes--;
                Cube cube = maintenance.cubes.next(true);
                if (cube == null) maintenance.discoverCubes = 0;
                else if (cube.needsSaving()) autosaveCubes.add(cube.getCoords());
            }
        }
    }
}
