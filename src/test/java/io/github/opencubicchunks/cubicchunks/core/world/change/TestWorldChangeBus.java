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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.opencubicchunks.cubicchunks.api.change.ChangeCauses;
import io.github.opencubicchunks.cubicchunks.api.change.ChangeFilter;
import io.github.opencubicchunks.cubicchunks.api.change.ChangeScope;
import io.github.opencubicchunks.cubicchunks.api.change.CubeChange;
import io.github.opencubicchunks.cubicchunks.api.change.CubeChangeBatch;
import io.github.opencubicchunks.cubicchunks.api.change.IWorldChangeBus;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TestWorldChangeBus {

    private static final ResourceLocation PASTE = new ResourceLocation("testmod", "paste");
    private static final ResourceLocation MACHINE = new ResourceLocation("testmod", "machine");

    /** A cube's pending change with what its cube was handed back on drain. */
    private static final class FakeCube {
        final PendingChange pending;
        long version;
        ResourceLocation[] causes;

        FakeCube(int x, int y, int z) {
            pending = new PendingChange(new CubePos(x, y, z), (v, c) -> {
                version = v;
                causes = c;
            });
        }
    }

    private static long bit(int localX, int localY, int localZ) {
        return 1L << CubeChange.subBlockIndex(localX, localY, localZ);
    }

    @Test public void manyWritesToACubeInATickMakeOneChange() {
        ChangeJournal journal = new ChangeJournal();
        FakeCube a = new FakeCube(0, 0, 0);
        FakeCube b = new FakeCube(1, 0, 0);
        AtomicLong counter = new AtomicLong(100);
        // a paste of every block of cube a, then one block of b, then a again
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            journal.record(a.pending, bit(x, y, z), PASTE);
        }
        journal.record(b.pending, bit(3, 3, 3), ChangeCauses.UNKNOWN);
        journal.record(a.pending, bit(0, 0, 0), ChangeCauses.UNKNOWN);

        List<CubeChange> changes = journal.drain(counter::getAndIncrement);
        assertEquals(2, changes.size());
        CubeChange first = changes.get(0);
        assertEquals("Cubes come out in the order they were first written", new CubePos(0, 0, 0), first.pos());
        assertEquals("Every sub-block of a was written", -1L, first.mask());
        assertEquals(new HashSet<>(Arrays.asList(PASTE, ChangeCauses.UNKNOWN)), first.causes());
        assertEquals(100, first.version());
        assertEquals(101, changes.get(1).version());
        assertEquals(bit(3, 3, 3), changes.get(1).mask());
        assertFalse("Another sub-block was not written", changes.get(1).touches(4, 0, 3));
        assertTrue("A block in the same 4x4x4 sub-block counts as written", changes.get(1).touches(0, 1, 2));

        assertEquals("The cube is handed its version", 100, a.version);
        assertEquals(2, a.causes.length);
        assertEquals(101, b.version);
        assertTrue(journal.isEmpty());
        assertTrue(a.pending.isEmpty());
    }

    @Test public void aDrainedCubeIsQueuedAgainByItsNextWrite() {
        ChangeJournal journal = new ChangeJournal();
        FakeCube a = new FakeCube(5, -3, 2);
        AtomicLong counter = new AtomicLong(1);
        journal.record(a.pending, bit(1, 1, 1), PASTE);
        assertEquals(1, journal.drain(counter::getAndIncrement).size());
        assertTrue("Nothing pending means an empty drain", journal.drain(counter::getAndIncrement).isEmpty());

        journal.record(a.pending, bit(15, 15, 15), MACHINE);
        List<CubeChange> next = journal.drain(counter::getAndIncrement);
        assertEquals(1, next.size());
        assertEquals("The mask starts over after a drain", bit(15, 15, 15), next.get(0).mask());
        assertEquals("So do the causes", Collections.singleton(MACHINE), next.get(0).causes());
        assertEquals(2, next.get(0).version());
    }

    @Test public void aWriteOfNoBitsIsNotAChange() {
        ChangeJournal journal = new ChangeJournal();
        journal.record(new FakeCube(0, 0, 0).pending, 0, PASTE);
        assertTrue(journal.isEmpty());
    }

    @Test public void generationAndSettlingAreNotJournaledAndNaturalOnlyWhenCounted() {
        assertFalse("Generation is never an edit", ChangeJournal.admits(ChangeCauses.GENERATION, true, Long.MIN_VALUE, 0));
        assertFalse("Not even a copy of the cause",
                ChangeJournal.admits(new ResourceLocation("cubicchunks", "generation"), true, Long.MIN_VALUE, 0));
        assertFalse("Natural ticks are left out by default", ChangeJournal.admits(ChangeCauses.NATURAL, false, Long.MIN_VALUE, 0));
        assertTrue("and journaled when counted", ChangeJournal.admits(ChangeCauses.NATURAL, true, Long.MIN_VALUE, 0));
        assertTrue(ChangeJournal.admits(ChangeCauses.UNKNOWN, false, Long.MIN_VALUE, 0));
        assertFalse("A settling cube's writes are not edits", ChangeJournal.admits(PASTE, false, 200, 199));
        assertTrue("and are again once it settles", ChangeJournal.admits(PASTE, false, 200, 200));
    }

    @Test public void scopesNestAndDefaultToUnknown() {
        assertEquals(0, ChangeScope.depth());
        assertSame(ChangeCauses.UNKNOWN, ChangeScope.current());
        ChangeScope.push(ChangeCauses.NATURAL);
        try {
            ChangeScope.push(PASTE);
            try {
                assertSame(PASTE, ChangeScope.current());
            } finally {
                ChangeScope.pop();
            }
            assertSame(ChangeCauses.NATURAL, ChangeScope.current());
        } finally {
            ChangeScope.pop();
        }
        assertSame(ChangeCauses.UNKNOWN, ChangeScope.current());
        try {
            ChangeScope.pop();
            fail("A pop with nothing pushed is a bug in the caller");
        } catch (IllegalStateException expected) {
        }
    }

    @Test public void filtersPickByCauseAndDimension() {
        java.util.Set<ResourceLocation> natural = Collections.singleton(ChangeCauses.NATURAL);
        java.util.Set<ResourceLocation> mixed = new HashSet<>(Arrays.asList(ChangeCauses.NATURAL, PASTE));
        assertFalse(ChangeFilter.edits().test(0, natural));
        assertTrue("A change with natural and another cause is an edit", ChangeFilter.edits().test(0, mixed));
        assertTrue(ChangeFilter.everything().test(0, natural));
        assertTrue(ChangeFilter.causes(PASTE).test(0, mixed));
        assertFalse(ChangeFilter.causes(MACHINE).test(0, mixed));
        assertFalse(ChangeFilter.edits().inDimension(-1).test(0, mixed));
        assertTrue(ChangeFilter.edits().inDimension(-1).test(-1, mixed));
    }

    @Test public void versionsOnlyGrowAcrossASaveAndLoad() {
        ChangeVersions versions = new ChangeVersions(ChangeVersions.NAME);
        long last = 0;
        for (int i = 0; i < 5; i++) {
            long v = versions.next();
            assertTrue(v > last);
            last = v;
        }
        NBTTagCompound saved = versions.writeToNBT(new NBTTagCompound());
        // a version handed out after the save is lost in a crash
        long lostInCrash = versions.next();

        ChangeVersions loaded = new ChangeVersions(ChangeVersions.NAME);
        loaded.readFromNBT(saved);
        long afterLoad = loaded.next();
        assertTrue("A version handed out before the crash is not handed out again", afterLoad > lostInCrash);
        assertEquals(saved.getLong("next") + ChangeVersions.LOAD_STEP, afterLoad);
        assertTrue("The moved-on counter is saved at the next save", loaded.isDirty());
    }

    @Test public void theBusDeliversWhatPassesEachFilterAndIsolatesListeners() {
        WorldChangeBus bus = new WorldChangeBus();
        CubeChange edit = new CubeChange(new CubePos(0, 0, 0), 1, 1, Collections.singleton(PASTE));
        CubeChange natural = new CubeChange(new CubePos(1, 0, 0), 2, 1, Collections.singleton(ChangeCauses.NATURAL));
        List<CubeChange> tick = Arrays.asList(edit, natural);

        List<List<CubeChange>> editsSeen = new ArrayList<>();
        List<List<CubeChange>> allSeen = new ArrayList<>();
        List<List<CubeChange>> netherSeen = new ArrayList<>();
        bus.subscribe(ChangeFilter.edits(), b -> {
            throw new RuntimeException("a broken listener");
        });
        IWorldChangeBus.Subscription edits = bus.subscribe(ChangeFilter.edits(), b -> editsSeen.add(b.changes()));
        bus.subscribe(ChangeFilter.everything(), b -> allSeen.add(b.changes()));
        bus.subscribe(ChangeFilter.everything().inDimension(-1), b -> netherSeen.add(b.changes()));

        bus.dispatch(null, 0, 10, tick, pos -> null);
        assertEquals("A broken listener does not stop the others", 1, editsSeen.size());
        assertEquals(Collections.singletonList(edit), editsSeen.get(0));
        assertEquals(tick, allSeen.get(0));
        assertTrue("Another dimension's listener sees nothing", netherSeen.isEmpty());

        edits.close();
        edits.close();
        bus.dispatch(null, 0, 11, tick, pos -> null);
        assertEquals("A closed subscription gets nothing more", 1, editsSeen.size());
        assertEquals(2, allSeen.size());
    }

    @Test public void snapshotsAreTakenOnlyDuringTheListenerCall() {
        WorldChangeBus bus = new WorldChangeBus();
        CubePos pos = new CubePos(4, 5, 6);
        CubeBlockCopy air = CubeBlockCopy.of(pos, null);
        AtomicReference<CubeChangeBatch> kept = new AtomicReference<>();
        AtomicReference<Object> taken = new AtomicReference<>();
        bus.subscribe(ChangeFilter.edits(), b -> {
            kept.set(b);
            taken.set(b.snapshot(pos));
            assertNull("A cube no longer loaded has no copy", b.snapshot(new CubePos(0, 0, 0)));
        });
        bus.dispatch(null, 0, 1, Collections.singletonList(new CubeChange(pos, 1, 1, Collections.singleton(PASTE))),
                p -> p.equals(pos) ? air : null);
        assertSame(air, taken.get());
        try {
            kept.get().snapshot(pos);
            fail("A batch kept past its call cannot copy");
        } catch (IllegalStateException expected) {
        }
    }
}
