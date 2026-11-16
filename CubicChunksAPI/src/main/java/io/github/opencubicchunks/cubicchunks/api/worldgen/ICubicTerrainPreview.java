package io.github.opencubicchunks.cubicchunks.api.worldgen;

import net.minecraft.block.state.IBlockState;
import net.minecraft.world.biome.Biome;

/**
 * Optional, seed-only surface representation for distant renderers. This does not
 * generate, load, populate or save gameplay cubes. Implementations must use the
 * same content rules as their cube generator and must not access a live world.
 * Native chunk snapshots supersede these SURFACE previews after exploration.
 *
 * <p><b>Threads.</b> A preview is shared: it must be safe to use from any number of threads
 * at once, and {@link #createTerrainPreviewSampler} may be called concurrently. A
 * {@link Sampler} is confined to one thread at a time; the pools that run samplers keep one a
 * thread and use it for many tasks in turn, so a sampler may be called again for any column
 * after any earlier one, and may be handed to another thread between tasks (never during one).
 *
 * <p><b>Caches.</b> Anything a preview or a sampler keeps from one call to the next must be a
 * pure function of the preview's inputs (the seed, the world's definitions and generator
 * options) and the coordinates asked for, so that a column samples the same whatever was
 * sampled before it, on whatever thread. Scratch buffers a sampler reuses and overwrites on
 * each call are not caches. A cache shared by the samplers of one preview must itself be safe
 * across threads.
 */
public interface ICubicTerrainPreview {
    /**
     * A new sampler, confined to one thread at a time. May be called from several threads at
     * once; different samplers may run concurrently.
     */
    Sampler createTerrainPreviewSampler();

    interface Sampler {
        /** Emit complete, contiguous bottom-to-top runs covering the world allocation. */
        void sampleColumn(int blockX, int blockZ, ColumnConsumer consumer);

        /**
         * The same, for a preview cell {@code footprint} blocks wide centred on the column
         * (1 at full detail, a power of two when coarser). A sampler may represent features
         * smaller than the cell by their cover (a forest canopy instead of single trees); by
         * default it samples the exact column.
         */
        default void sampleColumn(int blockX, int blockZ, int footprint, ColumnConsumer consumer) {
            sampleColumn(blockX, blockZ, consumer);
        }
    }

    interface ColumnConsumer {
        void begin(Biome biome);

        /**
         * Absolute Y, exclusive top. Supports non-emitting solid, transparent and
         * light-filtering states (leaves, logs) and vanilla stationary source water.
         * Sky light falls by a filtering state's opacity per block. Water previews
         * assume a surface column with no underwater air or emitters. Other fluids
         * and emitters fail explicitly; they are not air.
         */
        void addRun(int minimumY, int maximumYExclusive, IBlockState state);
    }
}
