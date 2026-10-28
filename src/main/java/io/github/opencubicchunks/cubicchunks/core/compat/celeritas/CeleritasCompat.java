/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.compat.celeritas;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.world.IColumnInternal;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkStatus;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer;

/**
 * Celeritas draws a world as columns of 16 sections read from {@code Chunk.getBlockStorageArray()}, and learns
 * about columns from {@code ChunkProviderClient.loadChunk}. In a cubic world the sections are cubes over the
 * world's whole height and the client loads columns through its own cache, so these hooks answer Celeritas from
 * cubes instead, and a ready column has render sections only for the cubes the client holds. The selectable
 * Celeritas mixins call the section queries; the client cube cache and cube packets call the rest.
 */
public final class CeleritasCompat {
    /** Checked as a resource so nothing from Celeritas loads unless it is installed. */
    private static final boolean PRESENT = CeleritasCompat.class.getClassLoader()
            .getResource("org/embeddedt/embeddium/impl/render/chunk/map/ChunkTrackerHolder.class") != null;

    private CeleritasCompat() {}

    public static boolean cubic(@Nullable World world) {
        return world != null && ((ICubicWorld) world).isCubicWorld();
    }

    /** Lowest section Celeritas should manage. */
    public static int minSection(World world) {
        return Coords.blockToCube(((ICubicWorld) world).getMinHeight());
    }

    /** One past the highest section Celeritas should manage. */
    public static int maxSection(World world) {
        return Coords.blockToCube(((ICubicWorld) world).getMaxHeight());
    }

    /**
     * The loaded cube's blocks at a section, or null when the cube is absent. A cube without storage reads as its
     * implicit sky light ({@link EmptyCubeSection}), or as null where that is full sky light throughout.
     */
    @Nullable
    public static ExtendedBlockStorage section(World world, int x, int y, int z) {
        ICube cube = ((ICubicWorld) world).getCubeCache().getLoadedCube(x, y, z);
        if (cube == null) {
            return null;
        }
        ExtendedBlockStorage storage = cube.getStorage();
        if (storage != null || !world.provider.hasSkyLight()) {
            return storage;
        }
        IColumnInternal column = (IColumnInternal) cube.getColumn();
        return EmptyCubeSection.of(y, column::getTopYWithStaging);
    }

    /** The loaded cube's tile entities at a section; a cubic column keeps none of its own. */
    public static Map<BlockPos, TileEntity> tileEntities(World world, int x, int y, int z) {
        ICube cube = ((ICubicWorld) world).getCubeCache().getLoadedCube(x, y, z);
        return cube == null ? Collections.emptyMap() : cube.getTileEntityMap();
    }

    /** A column arrived through Cubature's client cache, which bypasses the call Celeritas listens on. */
    public static void columnLoaded(World world, int x, int z) {
        if (PRESENT) {
            Tracker.added(world, x, z);
        }
    }

    /** The Y of each cube the client holds in a column; Celeritas gives a ready column sections only for these. */
    public static int[] loadedCubeYs(World world, int x, int z) {
        Chunk column = ((ICubicWorld) world).getCubeCache().getLoadedColumn(x, z);
        return column == null ? new int[0]
                : ((IColumn) column).getLoadedCubes().stream().mapToInt(ICube::getY).toArray();
    }

    /** A cube's blocks arrived: a ready column gains its render section, which builds if the cube has blocks. */
    public static void cubeLoaded(Cube cube) {
        cubeChanged(cube);
    }

    /** A cube left the client: its render section and mesh go with it. */
    public static void cubeUnloaded(Cube cube) {
        cubeChanged(cube);
    }

    // Celeritas may be searching its section graph on another thread (the shadow pass's async occlusion), and adding
    // or removing a section then throws, so cube changes wait for the point where it applies its own column events.
    private static final Set<CubePos> changedCubes = new HashSet<>();

    private static void cubeChanged(Cube cube) {
        if (PRESENT) {
            changedCubes.add(cube.getCoords());
        }
    }

    /**
     * Celeritas is applying its column events with no graph search running: each changed cube gains a section when
     * it is loaded in a ready column, and loses it otherwise.
     */
    public static void applyCubeChanges(@Nullable World world) {
        if (changedCubes.isEmpty()) {
            return;
        }
        if (cubic(world)) {
            Tracker.apply(world, changedCubes);
        }
        changedCubes.clear();
    }

    /** Separate so the tracker classes resolve only when Celeritas is present. */
    private static final class Tracker {
        static void added(World world, int x, int z) {
            // Block and light data arrive with each cube, so the column is complete as far as Celeritas knows.
            ChunkTrackerHolder.get(world).onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
        }

        static void apply(World world, Set<CubePos> cubes) {
            CeleritasWorldRenderer renderer = CeleritasWorldRenderer.instanceNullable();
            RenderSectionManager sections = renderer == null ? null : renderer.getRenderSectionManager();
            if (sections == null) {
                return;
            }
            LongCollection ready = ChunkTrackerHolder.get(world).getReadyChunks();
            for (CubePos pos : cubes) {
                // A column that is not ready yet adds its loaded cubes when it becomes ready.
                if (((ICubicWorld) world).getCubeCache().getLoadedCube(pos) != null
                        && ready.contains(ChunkPos.asLong(pos.getX(), pos.getZ()))) {
                    sections.onSectionAdded(pos.getX(), pos.getY(), pos.getZ());
                } else {
                    sections.onSectionRemoved(pos.getX(), pos.getY(), pos.getZ());
                }
            }
        }
    }
}
