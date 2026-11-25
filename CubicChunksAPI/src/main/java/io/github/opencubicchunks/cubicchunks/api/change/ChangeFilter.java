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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Which changes a subscriber receives. Immutable; each method returns a new filter.
 *
 * <pre>{@code
 * ChangeFilter.edits()                         // every cause but natural ticks, every dimension
 * ChangeFilter.edits().inDimension(0)          // the same, overworld only
 * ChangeFilter.causes(MY_PASTE).inDimension(0) // only changes with this cause
 * ChangeFilter.everything()                    // natural ticks too, when the server counts them
 * }</pre>
 */
public final class ChangeFilter {

    private static final ChangeFilter EDITS = new ChangeFilter(false, null, null);
    private static final ChangeFilter EVERYTHING = new ChangeFilter(true, null, null);

    private final boolean includeNatural;
    @Nullable private final Set<ResourceLocation> causes;
    @Nullable private final Integer dimension;

    private ChangeFilter(boolean includeNatural, @Nullable Set<ResourceLocation> causes, @Nullable Integer dimension) {
        this.includeNatural = includeNatural;
        this.causes = causes;
        this.dimension = dimension;
    }

    /** Changes with any cause but {@link ChangeCauses#NATURAL}. A change with natural and another cause passes. */
    public static ChangeFilter edits() {
        return EDITS;
    }

    /** Every change, natural ticks included. */
    public static ChangeFilter everything() {
        return EVERYTHING;
    }

    /** Only changes with at least one of these causes. */
    public static ChangeFilter causes(ResourceLocation... causes) {
        Set<ResourceLocation> set = new HashSet<>();
        Collections.addAll(set, causes);
        if (set.isEmpty()) {
            throw new IllegalArgumentException("A cause filter needs at least one cause");
        }
        return new ChangeFilter(set.contains(ChangeCauses.NATURAL), Collections.unmodifiableSet(set), null);
    }

    /** This filter, limited to one dimension. */
    public ChangeFilter inDimension(int dimension) {
        return new ChangeFilter(includeNatural, causes, dimension);
    }

    /** Whether a change in {@code dimension} with these causes passes. */
    public boolean test(int dimension, Set<ResourceLocation> changeCauses) {
        if (this.dimension != null && this.dimension != dimension) {
            return false;
        }
        if (causes != null) {
            for (ResourceLocation cause : changeCauses) {
                if (causes.contains(cause)) {
                    return true;
                }
            }
            return false;
        }
        if (includeNatural) {
            return true;
        }
        for (ResourceLocation cause : changeCauses) {
            if (!ChangeCauses.NATURAL.equals(cause)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the filter can pass anything in this dimension; lets the bus skip a listener for a whole batch. */
    public boolean acceptsDimension(int dimension) {
        return this.dimension == null || this.dimension == dimension;
    }

    @Override public String toString() {
        return "ChangeFilter{natural=" + includeNatural + ", causes=" + causes + ", dimension=" + dimension + "}";
    }
}
