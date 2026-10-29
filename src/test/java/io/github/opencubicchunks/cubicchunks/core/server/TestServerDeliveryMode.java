package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.DeliveryMode;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestServerDeliveryMode {
    @After public void restoreDefaults() {
        ServerDeliveryMode.clear();
        CubicChunksConfig.deliveryMode = DeliveryMode.BASELINE;
    }

    @Test public void eachStageKeepsEverythingTheStageBeforeItDoes() {
        boolean[][] expected = {
                {false, false, false},  // BASELINE
                {true, false, false},   // MAINTENANCE
                {true, true, false},    // SAVE
                {true, true, true}};    // PREFETCH
        DeliveryMode[] stages = DeliveryMode.values();
        assertEquals(expected.length, stages.length);
        for (int i = 0; i < stages.length; i++) {
            boolean[] actual = {stages[i].incremental(), stages[i].continuousSave(), stages[i].prefetch()};
            assertArrayEquals(stages[i].name(), expected[i], actual);
        }
    }

    @Test public void worldsCannotReadTheModeBeforeTheServerResolvesIt() {
        try {
            ServerDeliveryMode.get();
            fail("A world built outside a server start must not pick a delivery mode");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("server starts"));
        }
    }

    @Test public void aConfigChangeAppliesFromTheNextServerStart() {
        CubicChunksConfig.deliveryMode = DeliveryMode.SAVE;
        ServerDeliveryMode.resolve();
        CubicChunksConfig.deliveryMode = DeliveryMode.BASELINE;
        assertEquals(DeliveryMode.SAVE, ServerDeliveryMode.get());

        ServerDeliveryMode.clear();
        CubicChunksConfig.deliveryMode = DeliveryMode.PREFETCH;
        ServerDeliveryMode.resolve();
        assertEquals(DeliveryMode.PREFETCH, ServerDeliveryMode.get());
    }
}
