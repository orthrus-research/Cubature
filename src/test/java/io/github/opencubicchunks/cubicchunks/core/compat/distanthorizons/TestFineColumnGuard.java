package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestFineColumnGuard {
    private static final byte DOWN_SAMPLED = -1, EMPTY = 0, SURFACE = 5, LIGHT = 9;

    @Test public void answersAreToldApartFromDhPropagation() {
        assertTrue(FineColumnGuard.answer(true, true));
        assertTrue(FineColumnGuard.answer(true, false));
        assertFalse("DH propagating up", FineColumnGuard.answer(null, true));
        assertFalse("DH propagating down", FineColumnGuard.answer(true, null));
        assertFalse("1-block answers set applyToChildren false", FineColumnGuard.answer(false, true));
    }

    @Test public void aCoarseAnswerKeepsAPreviewBuiltColumn() {
        assertTrue(FineColumnGuard.keeps(true, 7, SURFACE, true, SURFACE));
        assertTrue(FineColumnGuard.keeps(true, 10, SURFACE, true, SURFACE));
    }

    @Test public void everythingElseMergesAsDhDoes() {
        assertFalse("1-block answers", FineColumnGuard.keeps(true, 6, SURFACE, true, SURFACE));
        assertFalse("DH propagation", FineColumnGuard.keeps(false, 8, SURFACE, true, SURFACE));
        assertFalse("holes are filled", FineColumnGuard.keeps(true, 8, SURFACE, false, SURFACE));
        assertFalse("empty columns are filled", FineColumnGuard.keeps(true, 8, EMPTY, true, SURFACE));
        assertFalse("copied-down columns are replaced", FineColumnGuard.keeps(true, 8, DOWN_SAMPLED, true, SURFACE));
        assertFalse("more complete answers replace", FineColumnGuard.keeps(true, 8, SURFACE, true, LIGHT));
        assertFalse("native columns are not SURFACE", FineColumnGuard.keeps(true, 8, LIGHT, true, SURFACE));
    }
}
