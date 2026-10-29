package io.github.opencubicchunks.cubicchunks.core.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.network.play.server.SPacketPlayerPosLook;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A teleport into an unprepared column is held; a later teleport that moves the player replaces it,
 * and one that leaves the player in place (vanilla re-sending an unconfirmed teleport, or correcting a
 * move it rejects) does not.
 */
public class TestDeferredArrivals {
    private static final Set<SPacketPlayerPosLook.EnumFlags> ABSOLUTE = EnumSet.noneOf(SPacketPlayerPosLook.EnumFlags.class);
    private static final Set<SPacketPlayerPosLook.EnumFlags> RELATIVE = EnumSet.allOf(SPacketPlayerPosLook.EnumFlags.class);
    private final UUID player = UUID.randomUUID();
    // Only the column at (187, 187), holding block (3000.5, 3000.5), is unprepared.
    private final DeferredArrivals arrivals = new DeferredArrivals(null, (x, z) -> !(x == 187 && z == 187));

    /** A teleport of the player, who stands at (0.5, 130, 0.5) while any teleport is held. */
    private boolean teleport(double x, double y, double z, Set<SPacketPlayerPosLook.EnumFlags> relative) {
        return arrivals.defer(player, 0.5, 130, 0.5, 0, 0, x, y, z, 0, 0, relative, 100);
    }

    @Test public void aTeleportIntoAnUnpreparedColumnIsHeld() {
        assertTrue(teleport(3000.5, 160, 3000.5, ABSOLUTE));
        assertTrue(arrivals.holds(player));
    }

    @Test public void aTeleportInPlaceKeepsTheHold() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        // Vanilla's re-send and corrections: setPlayerLocation with the player's own position.
        assertFalse(teleport(0.5, 130, 0.5, ABSOLUTE));
        assertTrue(arrivals.holds(player));
        // The same through /tp ~ ~ ~.
        assertFalse(teleport(0, 0, 0, RELATIVE));
        assertTrue(arrivals.holds(player));
    }

    @Test public void aReadyTeleportElsewhereReplacesTheHold() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        assertFalse(teleport(40.5, 130, 0.5, ABSOLUTE));
        assertFalse(arrivals.holds(player));
    }
}
