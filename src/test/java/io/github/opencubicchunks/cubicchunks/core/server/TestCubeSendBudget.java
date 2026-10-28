package io.github.opencubicchunks.cubicchunks.core.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class TestCubeSendBudget {
    @Test public void exactByteAndTimeLimitsStopBetweenWholePackets() {
        CubeSendBudget budget = new CubeSendBudget();
        budget.reset(100, 50, 1000);
        budget.sent(400);
        assertTrue(budget.canSend(149));
        assertFalse(budget.canSend(150));
        budget.sent(600);
        assertFalse(budget.canSend(101));
        assertEquals(1000, budget.bytes());
    }

    @Test public void oversizedPacketAndElapsedLightingStillMakeOneUnitOfProgress() {
        CubeSendBudget budget = new CubeSendBudget();
        budget.reset(100, 50, 1000);
        assertTrue(budget.canSend(10000));
        budget.sent(1000000);
        assertFalse(budget.canSend(101));
        assertEquals(1000000, budget.bytes());
    }

    @Test public void disabledLimitsAndNanoTimeWrapRemainValid() {
        CubeSendBudget budget = new CubeSendBudget();
        budget.reset(Long.MAX_VALUE - 10, 50, 0);
        budget.sent(20000000);
        assertTrue(budget.canSend(Long.MIN_VALUE + 10));
        assertFalse(budget.canSend(Long.MIN_VALUE + 40));
        budget.reset(0, 0, 0);
        budget.sent(20000000);
        assertTrue(budget.canSend(Long.MAX_VALUE));
    }
}
