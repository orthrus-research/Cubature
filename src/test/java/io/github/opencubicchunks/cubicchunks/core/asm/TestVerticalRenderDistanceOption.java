package io.github.opencubicchunks.cubicchunks.core.asm;

import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestVerticalRenderDistanceOption {
    @Test public void configuredChoiceControlsAllCurrentVerticalMixinsAndKeepsDependencyConditions() throws Exception {
        CubicChunksMixinConfig plugin = new CubicChunksMixinConfig();
        Field conditionsField = CubicChunksMixinConfig.class.getDeclaredField("modDependencyConditions");
        conditionsField.setAccessible(true);
        @SuppressWarnings("unchecked") Object2BooleanMap<String> conditions = (Object2BooleanMap<String>) conditionsField.get(plugin);
        conditions.defaultReturnValue(true);
        Field choice = CubicChunksMixinConfig.BoolOptions.class.getDeclaredField("value"); choice.setAccessible(true);
        CubicChunksMixinConfig.BoolOptions option = CubicChunksMixinConfig.BoolOptions.VERT_RENDER_DISTANCE;
        boolean before = choice.getBoolean(option);
        String prefix = "io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.client.";
        List<String> names = new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/cubicchunks.mixins.selectable.json"), StandardCharsets.UTF_8)) {
            JsonObject config = new JsonParser().parse(reader).getAsJsonObject();
            for (JsonElement entry : config.getAsJsonArray("client")) {
                if (entry.getAsString().startsWith("client.vertviewdist.")) names.add(config.get("package").getAsString() + "." + entry.getAsString());
            }
        }
        assertFalse("No registered vertical-render-distance mixins found", names.isEmpty());
        try {
            choice.setBoolean(option, false);
            for (String name : names) assertFalse(name, plugin.shouldApplyMixin(name));
            assertTrue(plugin.shouldApplyMixin(prefix + "MixinChunkCache_Cubic"));
            choice.setBoolean(option, true);
            for (String name : names) assertTrue(name, plugin.shouldApplyMixin(name));
            String noOptifine = prefix + "vertviewdist.MixinRenderGlobalNoOptifine";
            assertTrue(names.contains(noOptifine));
            conditions.put(noOptifine, false);
            assertFalse(plugin.shouldApplyMixin(noOptifine));
        } finally { choice.setBoolean(option, before); }
    }
}
