package io.github.opencubicchunks.cubicchunks.core.asm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestVerticalRenderDistanceOption {
    private static final String PREFIX = "cubicchunks.mixins.selectable.";

    private static List<String> mixins(String configuration) throws Exception {
        List<String> names = new ArrayList<>();
        InputStream resource = TestVerticalRenderDistanceOption.class.getResourceAsStream("/" + configuration);
        assertNotNull(configuration, resource);
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            JsonObject config = new JsonParser().parse(reader).getAsJsonObject();
            assertFalse(configuration + " must not name a plugin", config.has("plugin"));
            for (String side : new String[] {"mixins", "client", "server"}) {
                for (JsonElement entry : config.getAsJsonArray(side)) names.add(entry.getAsString());
            }
        }
        return names;
    }

    private static List<String> applied(boolean celeritas, boolean demonica, boolean pathNavigator, boolean cubeArrays,
            boolean fastCollision, boolean vertical) throws Exception {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : CubicChunksMixinConfig.selectableConfigurations(
                celeritas, demonica, pathNavigator, cubeArrays, fastCollision, vertical).entrySet()) {
            if (entry.getValue()) names.addAll(mixins(entry.getKey()));
        }
        return names;
    }

    @Test public void configuredChoiceControlsAllCurrentVerticalMixinsAndKeepsDependencyConditions() throws Exception {
        List<String> vertical = new ArrayList<>(mixins(PREFIX + "vertviewdist.json"));
        vertical.addAll(mixins(PREFIX + "vertviewdist-vanillaterrain.json"));
        assertEquals(3, vertical.size());
        for (String name : vertical) assertTrue(name, name.startsWith("client.vertviewdist."));

        List<String> off = applied(false, false, false, true, false, false);
        for (String name : vertical) assertFalse(name, off.contains(name));
        assertTrue(off.contains("client.MixinChunkCache_Cubic"));

        List<String> on = applied(false, false, false, true, false, true);
        assertTrue(on.containsAll(vertical));

        List<String> celeritas = applied(true, true, false, true, false, true);
        assertTrue(celeritas.contains("client.vertviewdist.MixinEntityRenderer"));
        assertFalse(celeritas.contains("client.vertviewdist.MixinRenderGlobal"));
        assertFalse(celeritas.contains("client.vertviewdist.MixinRenderGlobalVanillaTerrain"));
        assertFalse(celeritas.contains("client.MixinRenderGlobalVanillaTerrain"));
        assertTrue(celeritas.contains("client.celeritas.MixinCeleritasWorldSlice"));
        assertTrue(celeritas.contains("client.demonica.MixinDemonicaWorldInfo"));
    }

    @Test public void everyChoiceAppliesEachSelectableMixinOnceAndExactlyOneOfEachAlternative() throws Exception {
        for (int choice = 0; choice < 64; choice++) {
            List<String> names = applied((choice & 1) != 0, (choice & 2) != 0, (choice & 4) != 0, (choice & 8) != 0,
                    (choice & 16) != 0, (choice & 32) != 0);
            assertEquals("A Mixin is registered by two configurations", names.size(), names.stream().distinct().count());
            assertTrue(names.contains("common.MixinDistantHorizonsLevel"));
            assertTrue(names.contains("client.MixinChunkCache_Cubic") != names.contains("client.MixinChunkCache_Vanilla"));
            assertEquals(names.contains("client.MixinChunkCache_Cubic"), names.contains("common.MixinChunkCache"));
            assertTrue(names.contains("common.MixinWorld_CollisionCheck") != names.contains("common.MixinWorld_SlowCollisionCheck"));
            assertEquals((choice & 4) != 0, names.contains("common.MixinPathNavigate"));
            assertEquals((choice & 4) != 0, names.contains("common.MixinWalkNodeProcessor"));
        }
    }
}
