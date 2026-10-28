package io.github.opencubicchunks.cubicchunks.core.lighting;

import org.junit.Test;
import static org.junit.Assert.*;

public class TestLightingRules {
    @Test public void absentStorageHasSkyOnlyAboveTheOpaqueSurface() {
        for (int top : new int[]{-129, -1, 0, 15, 63, 383}) {
            assertEquals(0, LightingRules.emptySkyLight(true, top, top - 1));
            assertEquals(0, LightingRules.emptySkyLight(true, top, top));
            assertEquals(15, LightingRules.emptySkyLight(true, top, top + 1));
            assertEquals(0, LightingRules.emptySkyLight(false, top, top + 1));
        }
        assertEquals(15, LightingRules.emptySkyLight(true, Integer.MIN_VALUE, -128));
        assertEquals(0, LightingRules.emptySkyLight(true, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test public void everyIncomingQuadrantStaysOnTheWaitingCubesAdjoiningFace() {
        for (int base : new int[]{-2080, -16, 0, 16, 4096}) {
            for (int axis=0; axis<3; axis++) for (int sign : new int[]{-1, 1}) {
                boolean[] touched = new boolean[4096];
                for (int quadrant=0; quadrant<4; quadrant++) {
                    for (int u=0; u<8; u++) for (int v=0; v<8; v++) {
                        int[] local = new int[3];
                        local[axis] = LightingRules.incomingFaceOffset(sign);
                        int a=axis==0?1:0, b=axis==2?1:2;
                        local[a]=((quadrant&1)<<3)+u; local[b]=((quadrant&2)<<2)+v;
                        int coordinate=base+local[axis], neighbor=coordinate+sign;
                        assertEquals(base>>4, coordinate>>4);
                        assertEquals((base>>4)+sign, neighbor>>4);
                        int index=(local[1]<<8)|(local[2]<<4)|local[0];
                        assertFalse("Quadrants overlap",touched[index]);touched[index]=true;
                    }
                }
                int count=0;for(boolean hit:touched)if(hit)count++;
                assertEquals(256,count);
            }
            assertEquals(0,LightingRules.incomingFaceOffset(0));
        }
    }
}
