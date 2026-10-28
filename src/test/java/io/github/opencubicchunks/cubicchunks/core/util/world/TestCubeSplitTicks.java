package io.github.opencubicchunks.cubicchunks.core.util.world;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** Actual named collection classes; block registration is unnecessary for their contracts. */
public class TestCubeSplitTicks {
    private static final class Entry extends NextTickListEntry {
        final int key;
        Entry(int key, int y, long time) {
            super(new BlockPos(8, y, 8), null);
            this.key = key;
            setScheduledTime(time);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Entry && key == ((Entry) other).key && position.equals(((Entry) other).position);
        }
        @Override public int hashCode() { return 1; }
    }
    private static Entry entry(int key, int y) { return new Entry(key, y, key); }
    private static CubePos cube(NextTickListEntry e) { return CubePos.fromBlockCoords(e.position); }

    private static void checkSet(CubeSplitTickSet set, Collection<? extends NextTickListEntry> expected) {
        Set<NextTickListEntry> reference = new HashSet<>(expected);
        assertEquals(reference, set); assertEquals(set, reference); assertEquals(reference.hashCode(), set.hashCode());
        for (int y : new int[] {-129, -128, -120, -1, 0, 15, 16, 255, 256, 375, 383}) {
            CubePos pos = CubePos.fromBlockCoords(8, y, 8);
            Set<NextTickListEntry> group = new HashSet<>();
            for (NextTickListEntry e : expected) if (cube(e).equals(pos)) group.add(e);
            assertEquals(group, set.getForCube(pos));
        }
    }
    private static void checkList(CubeSplitTickList list, List<? extends NextTickListEntry> expected) {
        assertEquals(expected.size(), list.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), list.get(i));
        for (int y : new int[] {-128, -120, -1, 0, 15, 16, 255, 256, 375, 383}) {
            CubePos pos = CubePos.fromBlockCoords(8, y, 8);
            Map<NextTickListEntry, Integer> group = new IdentityHashMap<>(), actual = new IdentityHashMap<>();
            for (NextTickListEntry e : expected) if (cube(e).equals(pos)) group.put(e, group.getOrDefault(e, 0) + 1);
            for (NextTickListEntry e : list.getForCube(pos)) actual.put(e, actual.getOrDefault(e, 0) + 1);
            assertEquals(group, actual);
        }
    }

    @Test public void setIteratorRemovalUpdatesBothIndexesAndKeepsIteratorStateChecks() {
        CubeSplitTickSet set = new CubeSplitTickSet();
        List<NextTickListEntry> remaining = new ArrayList<>(Arrays.asList(entry(1,-120),entry(2,0),entry(3,375)));
        set.addAll(remaining); Iterator<NextTickListEntry> iterator = set.iterator();
        try { iterator.remove(); fail("Removal before next should fail"); } catch (IllegalStateException expected) { }
        while (iterator.hasNext()) {
            remaining.remove(iterator.next()); iterator.remove(); checkSet(set, remaining);
            try { iterator.remove(); fail("Double removal should fail"); } catch (IllegalStateException expected) { }
        }
    }
    @Test public void clearAllowsReuseAndHasValueEquality() {
        CubeSplitTickSet set = new CubeSplitTickSet(); set.addAll(Arrays.asList(entry(1,-120),entry(2,375)));
        set.clear(); checkSet(set, Collections.emptyList());
        Entry replacement = entry(3,375); set.add(replacement); checkSet(set, Collections.singletonList(replacement));
    }
    @Test public void bulkRemovalAcceptsItsOwnSetAndCubeBucketViews() {
        CubeSplitTickSet set = new CubeSplitTickSet(); Entry keep = entry(3,375);
        set.addAll(Arrays.asList(entry(1,-120),entry(2,-120),keep));
        assertTrue(set.removeAll(Collections.unmodifiableSet(set.getForCube(CubePos.fromBlockCoords(8,-120,8)))));
        checkSet(set, Collections.singletonList(keep));
        assertTrue(set.removeAll(set)); checkSet(set, Collections.emptyList()); assertFalse(set.removeAll(set));
    }
    @Test public void retentionAndDefaultRemoveIfMaintainCubeGroups() {
        CubeSplitTickSet set = new CubeSplitTickSet(); Entry keep=entry(3,375);
        set.addAll(Arrays.asList(entry(1,-120),entry(2,0),keep));
        assertTrue(set.retainAll(Collections.singleton(keep))); checkSet(set, Collections.singletonList(keep));
        assertTrue(set.removeIf(e -> true)); checkSet(set, Collections.emptyList());
    }
    @Test public void collisionWrapperRetainsEqualEntriesWithDifferentSchedulingTimes() {
        CubeSplitTickSet set = new CubeSplitTickSet(); List<NextTickListEntry> expected = new ArrayList<>();
        for (int i=0; i<80; i++) { Entry e=entry(i, i < 40 ? -120 : 375); set.add(e); expected.add(e); }
        for (int i=0; i<80; i++) {
            Entry equal=new Entry(i, i < 40 ? -120 : 375, 10000+i);
            assertTrue(set.contains(equal)); assertFalse(set.add(equal));
        }
        checkSet(set, expected);
        for (int i=0; i<80; i+=2) {
            NextTickListEntry original=expected.get(i/2);
            assertTrue(set.remove(new Entry(((Entry)original).key,original.position.getY(),20000+i)));
            expected.remove(i/2);
        }
        checkSet(set, expected);
    }
    @Test public void listReplacementReturnsPreviousAndMovesCubeMembership() {
        CubeSplitTickList list=new CubeSplitTickList(); Entry old=entry(1,-120), replacement=entry(2,375);
        list.add(old); assertSame(old,list.set(0,replacement)); checkList(list,Collections.singletonList(replacement));
    }
    @Test public void listRemovalPreservesTheActualEqualOccurrence() {
        CubeSplitTickList list=new CubeSplitTickList(); Entry first=new Entry(1,-120,10), second=new Entry(1,-120,20);
        list.add(first); list.add(second); assertSame(second,list.remove(1)); checkList(list,Collections.singletonList(first));
        list.add(second); assertTrue(list.remove(new Entry(1,-120,30))); checkList(list,Collections.singletonList(second));
    }
    @Test public void iteratorRemovalAfterReplacementRemovesTheCurrentOccurrence() {
        CubeSplitTickList list=new CubeSplitTickList(); Entry old=entry(1,-120), replacement=entry(2,375);
        list.add(old); Iterator<NextTickListEntry> iterator=list.iterator(); assertSame(old,iterator.next());
        list.set(0,replacement); iterator.remove(); checkList(list,Collections.emptyList());
    }
    @Test public void listIteratorEditsAndSubListClearMaintainBothIndexes() {
        CubeSplitTickList list=new CubeSplitTickList(); Entry a=entry(1,-120),b=entry(2,0),c=entry(3,375),d=entry(4,256);
        list.addAll(Arrays.asList(a,b,c)); ListIterator<NextTickListEntry> iterator=list.listIterator(1);
        assertSame(b,iterator.next()); iterator.set(d); iterator.add(b); checkList(list,Arrays.asList(a,d,b,c));
        list.subList(1,3).clear(); checkList(list,Arrays.asList(a,c));
        list.clear(); checkList(list,Collections.emptyList()); list.add(b); checkList(list,Collections.singletonList(b));
    }
    @Test public void structuralChangesInvalidateInheritedListIteratorsAndSubLists() {
        CubeSplitTickList list=new CubeSplitTickList(); list.addAll(Arrays.asList(entry(1,-120),entry(2,375)));
        ListIterator<NextTickListEntry> iterator=list.listIterator(); List<NextTickListEntry> sub=list.subList(0,1);
        list.add(entry(3,0));
        try { iterator.next(); fail("Stale list iterator should fail"); } catch (ConcurrentModificationException expected) { }
        try { sub.get(0); fail("Stale sublist should fail"); } catch (ConcurrentModificationException expected) { }
        Iterator<NextTickListEntry> removal=list.iterator(); removal.next(); removal.remove();
        checkList(list,Arrays.asList(list.get(0),list.get(1)));
    }
}
