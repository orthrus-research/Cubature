/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

/**
 * Keeps a coarse preview answer from replacing LOD columns DH built from finer data.
 *
 * <p>DH merges a section's data into a stored section of the same detail column by column, replacing any column whose
 * generation step is the same or less complete. A cubic preview tile is stamped SURFACE whatever its detail, and DH
 * carries the least complete step up when it downsamples a section's four children into it, so a column DH built
 * from 1-block preview tiles is SURFACE too. A coarse section with any hole is requested again at its own detail, and
 * its answer, one preview sample per cell, then replaced every such column: terrain already seen at full detail showed
 * the answer's coarse cells wherever DH drew that section. A column built from native chunks carries a later step and
 * was never replaced.
 *
 * <p>This keeps such a column: an answer (a network response or a generated tile; DH sets both of its propagation
 * flags on those, and sets only one on its own propagation) coarser than 1-block cells does not replace a stored
 * SURFACE column with data by a SURFACE column. It still fills empty and copied-down columns, and any column with a more
 * complete step replaces as before. DH's own propagation is untouched, so finer data still reaches coarse sections.
 */
public final class FineColumnGuard {
    /** DH's generation steps (EDhApiWorldGenerationStep) and its 1-block section detail. */
    static final byte EMPTY = 0, SURFACE = 5;
    static final int BLOCK_DETAIL = 6;

    private FineColumnGuard() {}

    /** Whether an input is an answer, by DH's propagation flags (FullDataSourceV2 applyToChildren, applyToParent). */
    public static boolean answer(Boolean applyToChildren, Boolean applyToParent) {
        return Boolean.TRUE.equals(applyToChildren) && applyToParent != null;
    }

    /** Whether the stored column stays: a coarse answer's SURFACE column over a SURFACE column with data. */
    public static boolean keeps(boolean answer, int detail, byte stored, boolean storedHasData, byte input) {
        return answer && detail > BLOCK_DETAIL && storedHasData && stored == SURFACE && input == SURFACE;
    }
}
