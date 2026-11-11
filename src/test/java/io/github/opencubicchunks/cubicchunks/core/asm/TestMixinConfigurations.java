package io.github.opencubicchunks.cubicchunks.core.asm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The configurations {@link CubicChunksMixinConnector} registers: each exists, and no Mixin is listed twice. */
public class TestMixinConfigurations {
    private static final String[] CONFIGURATIONS = {
        "cubicchunks.mixins.core.json", "cubicchunks.mixins.fixes.json", "cubicchunks.mixins.selectable.json",
        "cubicchunks.mixins.selectable.celeritas.json", "cubicchunks.mixins.selectable.demonica.json",
        "cubicchunks.mixins.selectable.vanillaterrain.json", "cubicchunks.mixins.noncritical.json"};

    private static List<String> mixins(String configuration) throws Exception {
        List<String> names = new ArrayList<>();
        InputStream resource = TestMixinConfigurations.class.getResourceAsStream("/" + configuration);
        assertNotNull(configuration, resource);
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            JsonObject config = new JsonParser().parse(reader).getAsJsonObject();
            assertFalse(configuration + " must not name a plugin", config.has("plugin"));
            String root = config.get("package").getAsString();
            for (String side : new String[] {"mixins", "client", "server"}) {
                if (!config.has(side)) continue;
                for (JsonElement entry : config.getAsJsonArray(side)) names.add(root + "." + entry.getAsString());
            }
        }
        return names;
    }

    @Test public void everyMixinIsRegisteredByExactlyOneConfigurationAndExists() throws Exception {
        Set<String> seen = new HashSet<>();
        for (String configuration : CONFIGURATIONS) {
            List<String> names = mixins(configuration);
            assertFalse(configuration + " registers nothing", names.isEmpty());
            for (String name : names) {
                assertTrue(name + " is registered twice", seen.add(name));
                assertNotNull(name + " has no class", getClass().getResource("/" + name.replace('.', '/') + ".class"));
            }
        }
    }

    @Test public void vanillaTerrainMixinsAreTheOnlyOnesCeleritasReplaces() throws Exception {
        for (String name : mixins("cubicchunks.mixins.selectable.vanillaterrain.json")) {
            assertTrue(name, name.contains(".selectable.client.vertviewdist.MixinRenderGlobal"));
        }
        for (String name : mixins("cubicchunks.mixins.selectable.celeritas.json")) {
            assertTrue(name, name.contains(".selectable.client.celeritas."));
        }
    }
}
