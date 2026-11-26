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
package io.github.opencubicchunks.cubicchunks.core.world.change;

import io.github.opencubicchunks.cubicchunks.api.change.ChangeCauses;
import io.github.opencubicchunks.cubicchunks.api.change.CubeChange;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/**
 * A world's block changes since the last tick, one {@link PendingChange} per cube written. A cube joins the queue on
 * its first write after a drain; later writes in the same tick only mark it. {@link #drain} turns the queue into one
 * {@link CubeChange} per cube, stamping each with the next version and handing it back to its cube.
 */
public final class ChangeJournal {

    private final ConcurrentLinkedQueue<PendingChange> queue = new ConcurrentLinkedQueue<>();

    /**
     * Whether a write is journaled: not under generation, not a natural tick unless the server counts them, not into a
     * cube that is not fully populated yet (no player has it; its neighbors' population and fluids are still filling
     * it), and not while its cube is settling after population.
     */
    public static boolean admits(ResourceLocation cause, boolean countNatural, boolean fullyPopulated, long settlingUntil, long now) {
        if (!fullyPopulated) {
            return false;
        }
        if (cause == ChangeCauses.GENERATION || cause.equals(ChangeCauses.GENERATION)) {
            return false;
        }
        if (!countNatural && (cause == ChangeCauses.NATURAL || cause.equals(ChangeCauses.NATURAL))) {
            return false;
        }
        return settlingUntil <= now;
    }

    /** Records a write to a cube. Server thread. */
    public void record(PendingChange pending, long bits, ResourceLocation cause) {
        if (bits == 0) {
            return;
        }
        if (pending.mark(bits, cause)) {
            queue.add(pending);
        }
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Takes every pending change, in the order the cubes were first written.
     *
     * @param nextVersion hands out versions, larger each call
     * @return the changes, unmodifiable; empty when nothing was written
     */
    public List<CubeChange> drain(LongSupplier nextVersion) {
        if (queue.isEmpty()) {
            return Collections.emptyList();
        }
        List<CubeChange> changes = new ArrayList<>();
        PendingChange pending;
        while ((pending = queue.poll()) != null) {
            long mask = pending.mask;
            if (mask == 0) {
                continue;
            }
            ResourceLocation[] causes = pending.takeCauses();
            long version = nextVersion.getAsLong();
            pending.committer.commit(version, causes);
            changes.add(new CubeChange(pending.pos, version, mask, causeSet(causes)));
        }
        return Collections.unmodifiableList(changes);
    }

    private static Set<ResourceLocation> causeSet(ResourceLocation[] causes) {
        if (causes.length == 1) {
            return Collections.singleton(causes[0]);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(causes)));
    }
}
