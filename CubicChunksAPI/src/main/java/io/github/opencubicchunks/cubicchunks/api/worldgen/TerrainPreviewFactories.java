/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
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
package io.github.opencubicchunks.cubicchunks.api.worldgen;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/** The client terrain preview factories generator mods have registered, by name. */
public final class TerrainPreviewFactories {

    private static final ConcurrentHashMap<String, ICubicTerrainPreviewFactory> FACTORIES =
            new ConcurrentHashMap<>();

    private TerrainPreviewFactories() {
    }

    /**
     * Registers a generator's factory under a name of its own, by convention its mod ID. The same
     * name goes into {@link ClientPreviewOffer#factory()}. A name is registered once.
     */
    public static void register(String name, ICubicTerrainPreviewFactory factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A terrain preview factory needs a name");
        }
        if (FACTORIES.putIfAbsent(name, factory) != null) {
            throw new IllegalStateException("Terrain preview factory " + name + " is already registered");
        }
    }

    /** The factory registered under the name, or null. */
    @Nullable
    public static ICubicTerrainPreviewFactory get(String name) {
        return FACTORIES.get(name);
    }
}
