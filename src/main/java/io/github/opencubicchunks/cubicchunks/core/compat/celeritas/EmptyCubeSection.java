/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Celeritas as bundled by Demonica 0.9.0.
 * No Celeritas code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.compat.celeritas;

import java.util.function.IntBinaryOperator;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.core.lighting.LightingRules;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * The section Celeritas reads for a cube without storage. Such a cube is all air, and its sky light is implicit:
 * full above each column's top opaque block and none below it ({@link LightingRules#emptySkyLight}). Celeritas
 * reads a missing section as its own empty section, which has no sky array and so reads full sky light at any
 * depth: a cave cube that no light ever reached lit its walls as daylight. Separate from {@link CeleritasCompat}
 * so it loads without Celeritas.
 */
final class EmptyCubeSection {
    /** Every column covered: all air, no sky light. Shared, since Celeritas only reads sections. */
    private static final ExtendedBlockStorage DARK = new ExtendedBlockStorage(0, true);

    private EmptyCubeSection() {}

    /**
     * The section for the storage-less cube at cubeY, whose column tops topY gives by local x and z: null where
     * every block is open to the sky (Celeritas's own empty section reads that correctly), else one holding the
     * implicit sky light, taken from the tops now as the vanilla renderer's cache takes them.
     */
    @Nullable
    static ExtendedBlockStorage of(int cubeY, IntBinaryOperator topY) {
        int minY = Coords.cubeToMinBlock(cubeY), maxY = Coords.cubeToMaxBlock(cubeY);
        int[] tops = new int[256];
        boolean open = true, covered = true;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = topY.applyAsInt(x, z);
                tops[(z << 4) | x] = top;
                open &= top < minY;
                covered &= top >= maxY;
            }
        }
        if (open) {
            return null;
        }
        if (covered) {
            return DARK;
        }
        ExtendedBlockStorage section = new ExtendedBlockStorage(minY, true);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = tops[(z << 4) | x];
                for (int y = 0; y < 16; y++) {
                    section.setSkyLight(x, y, z, LightingRules.emptySkyLight(true, top, minY + y));
                }
            }
        }
        return section;
    }
}
