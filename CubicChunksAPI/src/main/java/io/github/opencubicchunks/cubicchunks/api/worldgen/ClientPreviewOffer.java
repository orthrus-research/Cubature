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

/**
 * What a cube generator's server side offers a joining client, so the client can build the world's
 * terrain preview itself (docs/CUBE-SOURCE.md, section 8). The host carries it to the client and
 * hands it to the factory registered under {@link #factory()}; the generator owns its meaning.
 */
public final class ClientPreviewOffer {

    /** The most recorded definitions a server may offer, in bytes. */
    public static final int MAXIMUM_DEFINITIONS_BYTES = 1 << 20;

    private final String factory;
    private final String identity;
    private final long seed;
    private final String generatorOptions;
    private final byte[] recordedDefinitions;

    /**
     * @param factory the name the generator's mod registered its factory under
     *        ({@link TerrainPreviewFactories#register})
     * @param identity a hash that fixes everything the preview depends on, as text
     * @param seed the world seed
     * @param generatorOptions the generator options the world was created with
     * @param recordedDefinitions the world's recorded definitions, opaque to the host
     */
    public ClientPreviewOffer(String factory, String identity, long seed, String generatorOptions,
            byte[] recordedDefinitions) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.seed = seed;
        this.generatorOptions = Objects.requireNonNull(generatorOptions, "generatorOptions");
        if (recordedDefinitions.length > MAXIMUM_DEFINITIONS_BYTES) {
            throw new IllegalArgumentException("Recorded definitions of " + recordedDefinitions.length
                    + " bytes exceed " + MAXIMUM_DEFINITIONS_BYTES);
        }
        this.recordedDefinitions = recordedDefinitions.clone();
    }

    public String factory() {
        return factory;
    }

    public String identity() {
        return identity;
    }

    public long seed() {
        return seed;
    }

    public String generatorOptions() {
        return generatorOptions;
    }

    /** A copy of the recorded definitions. */
    public byte[] recordedDefinitions() {
        return recordedDefinitions.clone();
    }
}
