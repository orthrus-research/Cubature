/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;

/**
 * A shader material for blocks DH cannot classify. DH 3.3.3 names materials by the block's class
 * and modern registry names ({@code grass_block}, {@code dirt}, {@code stone}...), so 1.12's
 * {@code minecraft:grass} and most modded rock, soil and plants reach shader packs' LOD passes as
 * UNKNOWN. Their Minecraft {@link Material} says what they are.
 */
public final class CubicDistantMaterials {
    private CubicDistantMaterials() {}

    /** The DH material name for a block's Minecraft material, or null to keep UNKNOWN. */
    public static String name(IBlockState state) {
        if (state == null) return null;
        Material m = state.getMaterial();
        if (m == Material.GRASS) return "GRASS";
        if (m == Material.GROUND || m == Material.CLAY) return "DIRT";
        if (m == Material.SAND) return "SAND";
        if (m == Material.ROCK) return "STONE";
        if (m == Material.WOOD) return "WOOD";
        if (m == Material.IRON || m == Material.ANVIL) return "METAL";
        // DH files cactus and mushrooms with leaves; plants and vines read the same at distance.
        if (m == Material.LEAVES
                || m == Material.PLANTS
                || m == Material.VINE
                || m == Material.CACTUS
                || m == Material.GOURD) return "LEAVES";
        if (m == Material.SNOW || m == Material.CRAFTED_SNOW || m == Material.PACKED_ICE)
            return "SNOW";
        if (m == Material.WATER) return "WATER";
        if (m == Material.LAVA) return "LAVA";
        return null;
    }
}
