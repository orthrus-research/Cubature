package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import java.util.function.IntBinaryOperator;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.biome.Biome;

/**
 * Whether a native view that is behind should hold new preview tiles (native view first): only while it would show a
 * player's ground. A cubic native view is a box; a player high above the ground, or deep below it, has a view of air or
 * rock whose ground only LOD can show, so holding the tiles for it leaves a pit where the ground should be.
 */
final class NativeGround {
    private NativeGround() {}

    /**
     * Whether any of the native views, four ints each (the player's block x and z, the view's bottom block Y and its
     * exclusive top), shows the ground at its player's column; with no views, as before, the tiles are held.
     */
    static boolean holds(int[] views, IntBinaryOperator surface) {
        if (views.length == 0) return true;
        for (int i = 0; i + 3 < views.length; i += 4)
            if (showsGround(surface.applyAsInt(views[i], views[i + 1]), views[i + 2], views[i + 3])) return true;
        return false;
    }

    /** The client feed's test for a column (CubicChunkSnapshot.sliceShowsSurface): its top block and the air above it inside the view. */
    static boolean showsGround(int surface, int bottom, int top) {
        return surface >= bottom && surface + 2 <= top;
    }

    /** The top non-air block of a column, from a preview sampler; {@code minY - 1} for a column of air. */
    static int topBlock(ICubicTerrainPreview.Sampler sampler, int x, int z, int minY) {
        int[] top = {minY - 1};
        sampler.sampleColumn(x, z, new ICubicTerrainPreview.ColumnConsumer() {
            @Override public void begin(Biome biome) {}

            @Override public void addRun(int bottom, int topExclusive, IBlockState state) {
                if (state.getMaterial() != Material.AIR) top[0] = topExclusive - 1;
            }
        });
        return top[0];
    }
}
