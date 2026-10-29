package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import org.junit.Test;
import static org.junit.Assert.*;

public class TestGenerationBudget {
    @Test public void columnWorkConsumesTheSameDeadlineAsCubeWork() {
        GenerationBudget budget = new GenerationBudget();
        budget.reset(100, 50, 784);
        assertTrue(budget.hasTime(149));
        assertFalse(budget.hasTime(150));
        assertFalse(budget.canGenerateCube(150));
    }

    @Test public void zeroAndExactCubeQuotasDoNotAllowAnExtraGeneration() {
        GenerationBudget budget = new GenerationBudget();
        for (int quota : new int[] {0, 1, 784}) {
            budget.reset(100, 50, quota);
            int generated = 0;
            while (budget.canGenerateCube(101)) { generated++; budget.generatedCube(); }
            assertEquals(quota, generated);
        }
    }

    @Test public void deadlineHandlesNanoTimeWrapAndReset() {
        GenerationBudget budget = new GenerationBudget();
        budget.reset(Long.MAX_VALUE - 10, 50, 1);
        assertTrue(budget.canGenerateCube(Long.MIN_VALUE + 10));
        assertFalse(budget.hasTime(Long.MIN_VALUE + 40));
        budget.generatedCube();
        budget.reset(100, 0, 1);
        assertFalse(budget.hasTime(100));
        budget.reset(200, 50, 1);
        assertTrue(budget.canGenerateCube(200));
    }
}
