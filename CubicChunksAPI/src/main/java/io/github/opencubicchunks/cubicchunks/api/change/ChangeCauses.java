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
package io.github.opencubicchunks.cubicchunks.api.change;

import net.minecraft.util.ResourceLocation;

/**
 * The causes Cubature itself gives block changes. A mod names its own with any {@link ResourceLocation} in its own
 * namespace, pushed with {@link ChangeScope#push}.
 */
public final class ChangeCauses {

    /**
     * Writes made while a cube is generated or populated. They form the cube's generated state and are never
     * journaled. A mod that writes generated content outside Cubature's population (its own structure pass, for
     * example) pushes this cause around those writes so they are not taken for edits.
     */
    public static final ResourceLocation GENERATION = new ResourceLocation("cubicchunks", "generation");

    /**
     * Writes from random ticks, ice and snow: grass spread, leaf decay, crop growth, snow layers. Journaled only
     * when the server counts them ({@code changeCountNaturalTicks}); most subscribers leave them out.
     */
    public static final ResourceLocation NATURAL = new ResourceLocation("cubicchunks", "natural");

    /** Writes made with no cause pushed: players, machines, commands, mods that name none. */
    public static final ResourceLocation UNKNOWN = new ResourceLocation("cubicchunks", "unknown");

    private ChangeCauses() {
    }
}
