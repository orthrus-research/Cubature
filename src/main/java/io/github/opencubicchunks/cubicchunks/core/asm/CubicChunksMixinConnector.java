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
        ConditionalMixin.of("cubicchunks.mixins.core.json", "cubicchunks.mixins.fixes.json").register();
        // Renderers are recognised by the mod the loader discovered or by their classes, checked as resources so
        // none loads this early. Demonica bundles Celeritas.
        CleanroomModDiscoverer mods = CleanroomModDiscoverer.instance();
        boolean demonica = mods.isModPresent("demonica")
                || present("net/coderbot/iris/uniforms/IrisExclusiveUniforms$WorldInfoUniforms.class");
        boolean celeritas = demonica || mods.isModPresent("celeritas")
                || present("org/taumc/celeritas/mixin/core/terrain/RenderGlobalMixin.class");
        CubicChunksMixinConfig.selectableConfigurations(celeritas, demonica)
                .forEach((configuration, applies) -> ConditionalMixin.of(applies, configuration).register());
        ConditionalMixin.of("cubicchunks.mixins.noncritical.json").register();
    }

    private static boolean present(String resource) {
        return CubicChunksMixinConnector.class.getClassLoader().getResource(resource) != null;
    }
}
