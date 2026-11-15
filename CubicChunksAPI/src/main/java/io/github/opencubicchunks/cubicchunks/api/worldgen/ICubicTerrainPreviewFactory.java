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

import javax.annotation.Nullable;

/**
 * Builds a generator's terrain preview on a client, from what the server sent at join
 * (docs/CUBE-SOURCE.md, section 8). A generator mod registers one with
 * {@link TerrainPreviewFactories#register} at mod construction.
 */
@FunctionalInterface
public interface ICubicTerrainPreviewFactory {

    /**
     * Builds the preview, or returns null to decline: the identity computed here differs from
     * {@link ClientPreviewInputs#identity()}, or a block or biome the definitions name is missing.
     * Runs off the client thread, on a thread of the host's; the factory starts none of its own.
     * The result must be a pure function of the inputs, with bounded memos.
     */
    @Nullable
    ICubicTerrainPreview create(ClientPreviewInputs inputs);
}
