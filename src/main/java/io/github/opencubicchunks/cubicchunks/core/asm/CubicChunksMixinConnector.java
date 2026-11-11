/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.asm;

import com.cleanroommc.cleanmix.ConditionalMixin;
import com.cleanroommc.discovery.CleanroomModDiscoverer;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

/**
 * Registers Cubature's Mixin configurations. CleanMix calls it from the JAR's {@code MixinConnector} manifest
 * attribute before Minecraft classes load; it must not touch a Minecraft, Forge or Cubature API class.
 */
public final class CubicChunksMixinConnector implements IMixinConnector {

    @Override
    public void connect() {
        ConditionalMixin.of("cubicchunks.mixins.core.json", "cubicchunks.mixins.fixes.json",
                "cubicchunks.mixins.selectable.json").register();
        // Renderers are recognised by the mod the loader discovered or by their classes, checked as resources so
        // none loads this early. Demonica bundles Celeritas.
        CleanroomModDiscoverer mods = CleanroomModDiscoverer.instance();
        boolean demonica = mods.isModPresent("demonica")
                || present("net/coderbot/iris/uniforms/IrisExclusiveUniforms$WorldInfoUniforms.class");
        boolean celeritas = demonica || mods.isModPresent("celeritas")
                || present("org/taumc/celeritas/mixin/core/terrain/RenderGlobalMixin.class");
        // Celeritas overwrites RenderGlobal.setupTerrain and draws terrain with its own section renderer, so the
        // vanilla terrain-path Mixins have nothing to hook. In their place Celeritas is taught the cubic height
        // and reads sections and entities from cubes.
        ConditionalMixin.of(celeritas, "cubicchunks.mixins.selectable.celeritas.json").register();
        // Demonica's Iris pipeline reports the vanilla floor and the biome at Y 0 to shader packs; its include
        // graph reads the pack files that complementaryCaveFog patches.
        ConditionalMixin.of(demonica, "cubicchunks.mixins.selectable.demonica.json").register();
        ConditionalMixin.of(!celeritas, "cubicchunks.mixins.selectable.vanillaterrain.json").register();
        ConditionalMixin.of("cubicchunks.mixins.noncritical.json").register();
    }

    private static boolean present(String resource) {
        return CubicChunksMixinConnector.class.getClassLoader().getResource(resource) != null;
    }
}
