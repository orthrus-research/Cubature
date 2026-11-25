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

import io.github.opencubicchunks.cubicchunks.api.change.ChangeFilter;
import io.github.opencubicchunks.cubicchunks.api.change.CubeChange;
import io.github.opencubicchunks.cubicchunks.api.change.CubeChangeBatch;
import io.github.opencubicchunks.cubicchunks.api.change.ICubeBlockCopy;
import io.github.opencubicchunks.cubicchunks.api.change.IWorldChangeBus;
import io.github.opencubicchunks.cubicchunks.api.change.IWorldChangeListener;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.annotation.Nullable;

/** The change bus: subscriptions, and each tick's dispatch of a world's drained changes to them. */
public final class WorldChangeBus implements IWorldChangeBus {

    public static final WorldChangeBus INSTANCE = new WorldChangeBus();

    private static final Logger LOGGER = LogManager.getLogger("cubicchunks/change");

    private final CopyOnWriteArrayList<Sub> subscriptions = new CopyOnWriteArrayList<>();
    @Nullable private volatile Thread dispatching;

    @Override
    public Subscription subscribe(ChangeFilter filter, IWorldChangeListener listener) {
        Sub sub = new Sub(Objects.requireNonNull(filter, "filter"), Objects.requireNonNull(listener, "listener"));
        subscriptions.add(sub);
        return sub;
    }

    public boolean hasSubscribers() {
        return !subscriptions.isEmpty();
    }

    /**
     * Hands one tick's changes in a world to every subscription whose filter passes some of them. Server thread.
     *
     * @param copier copies a loaded cube's blocks, or answers null when the cube is not loaded
     */
    public void dispatch(World world, int dimension, long tick, List<CubeChange> changes,
            Function<CubePos, ICubeBlockCopy> copier) {
        if (changes.isEmpty() || subscriptions.isEmpty()) {
            return;
        }
        dispatching = Thread.currentThread();
        try {
            for (Sub sub : subscriptions) {
                if (sub.closed || !sub.filter.acceptsDimension(dimension)) {
                    continue;
                }
                List<CubeChange> passed = filter(sub.filter, dimension, changes);
                if (passed.isEmpty()) {
                    continue;
                }
                Batch batch = new Batch(world, tick, passed, copier);
                try {
                    sub.listener.onChanges(batch);
                } catch (Throwable t) {
                    LOGGER.error("A change listener threw; other listeners still receive this tick's changes: {}", sub.listener, t);
                } finally {
                    batch.open = false;
                }
            }
        } finally {
            dispatching = null;
        }
    }

    private static List<CubeChange> filter(ChangeFilter filter, int dimension, List<CubeChange> changes) {
        List<CubeChange> passed = null;
        for (int i = 0; i < changes.size(); i++) {
            CubeChange change = changes.get(i);
            boolean pass = filter.test(dimension, change.causes());
            if (pass && passed != null) {
                passed.add(change);
            } else if (!pass && passed == null) {
                passed = new ArrayList<>(changes.subList(0, i));
            }
        }
        return passed == null ? changes : Collections.unmodifiableList(passed);
    }

    private final class Sub implements Subscription {
        final ChangeFilter filter;
        final IWorldChangeListener listener;
        volatile boolean closed;

        Sub(ChangeFilter filter, IWorldChangeListener listener) {
            this.filter = filter;
            this.listener = listener;
        }

        @Override public void close() {
            closed = true;
            subscriptions.remove(this);
        }
    }

    private final class Batch implements CubeChangeBatch {
        private final World world;
        private final long tick;
        private final List<CubeChange> changes;
        private final Function<CubePos, ICubeBlockCopy> copier;
        boolean open = true;

        Batch(World world, long tick, List<CubeChange> changes, Function<CubePos, ICubeBlockCopy> copier) {
            this.world = world;
            this.tick = tick;
            this.changes = changes;
            this.copier = copier;
        }

        @Override public World world() {
            return world;
        }

        @Override public long tick() {
            return tick;
        }

        @Override public List<CubeChange> changes() {
            return changes;
        }

        @Nullable @Override public ICubeBlockCopy snapshot(CubePos pos) {
            if (!open || dispatching != Thread.currentThread()) {
                throw new IllegalStateException("CubeChangeBatch.snapshot is callable only on the server thread, during onChanges");
            }
            return copier.apply(pos);
        }
    }
}
