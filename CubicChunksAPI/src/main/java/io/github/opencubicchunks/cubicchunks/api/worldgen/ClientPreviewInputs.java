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

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;

import javax.annotation.Nullable;

/**
 * What a client received at join for one dimension, and the host resources it may build a terrain
 * preview with. No world: a preview must not read or write a live world, a save directory or the
 * game directory.
 */
public final class ClientPreviewInputs {

    private final int dimension;
    private final ClientPreviewOffer offer;
    @Nullable private final ForkJoinPool buildPool;
    @Nullable private final Path derivedDataDirectory;

    public ClientPreviewInputs(int dimension, ClientPreviewOffer offer, @Nullable ForkJoinPool buildPool,
            @Nullable Path derivedDataDirectory) {
        this.dimension = dimension;
        this.offer = Objects.requireNonNull(offer, "offer");
        this.buildPool = buildPool;
        this.derivedDataDirectory = derivedDataDirectory;
    }

    /** The dimension the preview is for. */
    public int dimension() {
        return dimension;
    }

    /** The server's identity for the preview. A factory declines unless its own equals it. */
    public String identity() {
        return offer.identity();
    }

    public long seed() {
        return offer.seed();
    }

    public String generatorOptions() {
        return offer.generatorOptions();
    }

    /** A copy of the world's recorded definitions, as the server's generator wrote them. */
    public byte[] recordedDefinitions() {
        return offer.recordedDefinitions();
    }

    /**
     * The host's bounded pool for in-build parallelism, never the common pool; null means that
     * work stays on the calling thread. Callers must not shut it down.
     */
    @Nullable
    public ForkJoinPool buildPool() {
        return buildPool;
    }

    /** A cache folder of the client's for this preview identity, or null: memory only. */
    @Nullable
    public Path derivedDataDirectory() {
        return derivedDataDirectory;
    }
}
