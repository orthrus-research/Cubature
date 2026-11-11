/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.asm;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * The restart-time choices in {@code config/cubicchunks_mixin_config.json}, and the selectable Mixin
 * configurations those choices and the installed renderer enable. Plain Java: {@link CubicChunksMixinConnector}
 * reads it before Minecraft classes load, and the file is read once, on the first question.
 */
public final class CubicChunksMixinConfig {

    @Nonnull
    public static Logger LOGGER = LogManager.getLogger("CubicChunksMixinConfig");
    private static boolean loaded;

    private CubicChunksMixinConfig() {
    }

    /** Each selectable configuration and whether it applies, in registration order, for the configured choices. */
    public static Map<String, Boolean> selectableConfigurations(boolean celeritas, boolean demonica) {
        return selectableConfigurations(celeritas, demonica, BoolOptions.OPTIMIZE_PATH_NAVIGATOR.getValue(),
                BoolOptions.USE_CUBE_ARRAYS_INSIDE_CHUNK_CACHE.getValue(), BoolOptions.USE_FAST_COLLISION_CHECK.getValue(),
                BoolOptions.VERT_RENDER_DISTANCE.getValue());
    }

    static Map<String, Boolean> selectableConfigurations(boolean celeritas, boolean demonica, boolean pathNavigator,
            boolean cubeArrays, boolean fastCollision, boolean verticalRenderDistance) {
        Map<String, Boolean> configurations = new LinkedHashMap<>();
        String prefix = "cubicchunks.mixins.selectable.";
        configurations.put(prefix + "json", true);
        // Celeritas (alone or bundled in Demonica) overwrites RenderGlobal.setupTerrain and draws terrain with its own
        // section renderer, so the vanilla terrain-path mixins have nothing to hook. In their place, Celeritas is
        // taught the cubic height and reads sections and entities from cubes.
        configurations.put(prefix + "celeritas.json", celeritas);
        // Demonica's Iris pipeline reports the vanilla floor and the biome at Y 0 to shader packs; its include
        // graph reads the pack files that complementaryCaveFog patches.
        configurations.put(prefix + "demonica.json", demonica);
        configurations.put(prefix + "vanillaterrain.json", !celeritas);
        configurations.put(prefix + "pathnavigator.json", pathNavigator);
        configurations.put(prefix + "chunkcache-cubic.json", cubeArrays);
        configurations.put(prefix + "chunkcache-vanilla.json", !cubeArrays);
        configurations.put(prefix + "collision-fast.json", fastCollision);
        configurations.put(prefix + "collision-slow.json", !fastCollision);
        configurations.put(prefix + "vertviewdist.json", verticalRenderDistance);
        configurations.put(prefix + "vertviewdist-vanillaterrain.json", verticalRenderDistance && !celeritas);
        return configurations;
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        File folder = new File(".", "config");
        folder.mkdirs();
        File configFile = new File(folder, "cubicchunks_mixin_config.json");
        LOGGER.info("Loading configuration file " + configFile.getAbsolutePath());
        try {
            if (!configFile.exists())
                writeConfigToJson(configFile);
            readConfigFromJson(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public enum BoolOptions {
        OPTIMIZE_PATH_NAVIGATOR(false,
                "Enabling this option will optimize work of vanilla path navigator."
                        + "Using this option in some cases turn entity AI a little dumber."
                        + " Mob standing in a single axis aligned line with player in a middle of a"
                        + " chunk will not try to seek path to player outside of chunks if direct path is blocked."
                        + " You need to restart Minecraft to apply changes."),
        USE_CUBE_ARRAYS_INSIDE_CHUNK_CACHE(true,
                "Enabling this option will mix cube array into chunk cache"
                        + " for using in entity path navigator."
                        + " Potentially this will slightly reduce server tick time"
                        + " in presence of huge amount of living entities."
                        + " You need to restart Minecraft to apply changes."),
        USE_FAST_COLLISION_CHECK(false,
                "Enabling this option allow using fast collision check."
                        + " Fast collision check can reduce server lag."
                        + " You need to restart Minecraft to apply changes."),
        VERT_RENDER_DISTANCE(true,
                "Enabling this option will make the vertical view distance slider affect clientside vertical render distance." +
                        " When disabled, only serverside load distance is affected.");

        private final String description;
        private boolean value;

        private BoolOptions(boolean defaultValue, String description1) {
            description = description1;
            value = defaultValue;
        }

        public boolean getValue() {
            load();
            return value;
        }
    }

    private static void writeConfigToJson(File configFile) throws IOException {
        JsonWriter writer = new JsonWriter(new FileWriter(configFile));
        writer.setIndent(" ");
        writer.beginArray();
        for (BoolOptions configOption : BoolOptions.values()) {
            writer.beginObject();
            writer.name(configOption.name());
            writer.value(configOption.value);
            writer.name("description");
            writer.value(configOption.description);
            writer.endObject();
        }
        writer.endArray();
        writer.close();
    }

    private static void readConfigFromJson(File configFile) throws IOException {
        int expectingOptionsNumber = BoolOptions.values().length;
        JsonReader reader = new JsonReader(new FileReader(configFile));
        reader.beginArray();
        while(reader.hasNext()){
            reader.beginObject();
            next_object:while(reader.hasNext()){
                String name = reader.nextName();
                for(BoolOptions option:BoolOptions.values()){
                    if(option.name().equals(name)){
                        expectingOptionsNumber--;
                        option.value = reader.nextBoolean();
                        continue next_object;
                    }
                }
                reader.skipValue();
            }
            reader.endObject();
        }
        reader.endArray();
        reader.close();
        if (expectingOptionsNumber != 0)
            writeConfigToJson(configFile);
    }
}
