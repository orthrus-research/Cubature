/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 */
package io.github.opencubicchunks.cubicchunks.core.asm;

import com.cleanroommc.cleanmix.ConditionalMixin;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

/**
 * Registers Cubature's Mixin configurations. CleanMix calls it from the JAR's {@code MixinConnector} manifest
 * attribute before Minecraft classes load; it must not touch a Minecraft, Forge or Cubature API class.
 */
public final class CubicChunksMixinConnector implements IMixinConnector {

    @Override
    public void connect() {
        ConditionalMixin.of("cubicchunks.mixins.core.json", "cubicchunks.mixins.fixes.json",
                "cubicchunks.mixins.selectable.json", "cubicchunks.mixins.noncritical.json").register();
    }
}
