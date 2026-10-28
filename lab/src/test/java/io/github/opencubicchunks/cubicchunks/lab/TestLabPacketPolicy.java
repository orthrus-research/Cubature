package io.github.opencubicchunks.cubicchunks.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TestLabPacketPolicy {
    public static final class LegacyConfig { }
    public static final class PacketConfig { public static int limit = 64; }
    public static final class InvalidConfig { public int limit = 64; }

    @Test
    public void missingControlIsUnavailableRatherThanInvented() {
        assertNull(LabPacketPolicy.optionalInt(LegacyConfig.class, "limit"));
    }

    @Test
    public void experimentalRuntimeSuppliesItsActualLimit() {
        assertEquals(Integer.valueOf(64), LabPacketPolicy.optionalInt(PacketConfig.class, "limit"));
    }

    @Test(expected = IllegalStateException.class)
    public void incompatibleFieldShapeFailsInsteadOfReturningADefault() {
        LabPacketPolicy.optionalInt(InvalidConfig.class, "limit");
    }
}
