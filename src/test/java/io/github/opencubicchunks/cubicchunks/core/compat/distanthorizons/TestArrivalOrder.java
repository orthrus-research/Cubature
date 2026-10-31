package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class TestArrivalOrder {
    private static Map<Long, String> waiting(long... positions) {
        Map<Long, String> out = new HashMap<>();
        for (long pos : positions) out.put(pos, "task " + pos);
        return out;
    }

    @Test public void theEarliestWaitingRequestGoesFirstWhateverItsPosition() {
        ArrivalOrder order = new ArrivalOrder();
        for (long pos : new long[] {900, 5, 77}) order.arrived(pos);
        Map<Long, String> queue = waiting(5, 77, 900);
        assertEquals(900L, (long) order.first(queue).getKey());
        queue.remove(900L);
        assertEquals(5L, (long) order.first(queue).getKey());
    }

    @Test public void aRepeatKeepsItsPlaceAndAStartedSectionIsForgotten() {
        ArrivalOrder order = new ArrivalOrder();
        order.arrived(1);
        order.arrived(2);
        order.arrived(1);
        Map<Long, String> queue = waiting(1, 2);
        assertEquals(1L, (long) order.first(queue).getKey());
        queue.remove(1L);
        order.arrived(1); // asked again after it started: behind 2 now
        queue.put(1L, "again");
        assertEquals(2L, (long) order.first(queue).getKey());
    }

    @Test public void unseenEntriesArriveOnFirstSightAndNothingWaitingIsNull() {
        ArrivalOrder order = new ArrivalOrder();
        order.arrived(3);
        assertEquals(3L, (long) order.first(waiting(3, 4)).getKey());
        assertNull(order.first(waiting()));
    }

    @Test public void stampsOfSectionsNoLongerWaitingAreDropped() {
        ArrivalOrder order = new ArrivalOrder();
        for (long pos = 0; pos < 1000; pos++) order.arrived(pos);
        order.first(waiting(999));
        assertTrue(order.tracked() <= 256);
    }
}
