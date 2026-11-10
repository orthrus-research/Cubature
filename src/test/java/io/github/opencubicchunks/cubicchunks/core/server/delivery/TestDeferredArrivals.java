package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import static org.junit.Assert.*;

import net.minecraft.network.play.server.SPacketPlayerPosLook;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Held destinations outlive slow or failed work and release their host-owned requests on every exit. */
public class TestDeferredArrivals {
    private static final Set<SPacketPlayerPosLook.EnumFlags> ABSOLUTE = EnumSet.noneOf(SPacketPlayerPosLook.EnumFlags.class);
    private static final Set<SPacketPlayerPosLook.EnumFlags> RELATIVE = EnumSet.allOf(SPacketPlayerPosLook.EnumFlags.class);
    private final UUID player = new UUID(0, 1);
    private final RecordingHost host = new RecordingHost();
    private final DeferredArrivals arrivals = new DeferredArrivals(host);

    private record Column(int x, int z) { }

    private final class RecordingHost implements DeferredArrivals.Host {
        final Set<Column> unprepared = new HashSet<>(Set.of(new Column(187, 187)));
        final Map<UUID, Column> requests = new HashMap<>();
        final List<DeferredArrivals.Target> completed = new ArrayList<>();
        final List<Long> messages = new ArrayList<>();
        final List<ConcurrentGeneration.ArrivalStatus> watchdogs = new ArrayList<>();
        long now, nanos, readyAt = Long.MAX_VALUE;
        int registrations, withdrawals;
        boolean present = true, failed, immediate;
        Consumer<DeferredArrivals.Target> completing = target -> { };

        @Override public boolean immediate(int x, int z) { return immediate || !unprepared.contains(new Column(x, z)); }
        @Override public ConcurrentGeneration.ArrivalStatus status(int x, int z) {
            return new ConcurrentGeneration.ArrivalStatus(now >= readyAt ? ConcurrentGeneration.ArrivalState.READY
                    : failed ? ConcurrentGeneration.ArrivalState.FAILED : ConcurrentGeneration.ArrivalState.PENDING,
                    failed ? "test failure" : "test preparation");
        }
        @Override public void hold(UUID owner, int x, int z) {
            Column key = new Column(x, z);
            Column previous = requests.put(owner, key);
            if (!key.equals(previous)) {
                registrations++;
                if (previous != null) withdrawals++;
            }
        }
        @Override public void release(UUID owner) { if (requests.remove(owner) != null) withdrawals++; }
        @Override public boolean present(UUID owner) { return present; }
        @Override public void complete(UUID owner, DeferredArrivals.Target target) {
            completing.accept(target);
            completed.add(target);
        }
        @Override public void preparing(UUID owner, boolean failed) { messages.add(nanos); }
        @Override public void watchdog(UUID owner, DeferredArrivals.Target target, long ticks, ConcurrentGeneration.ArrivalStatus status) {
            watchdogs.add(status);
        }
    }

    /** A player stands at (0.5, 130, 0.5) while the teleport is held. */
    private boolean teleport(double x, double y, double z, Set<SPacketPlayerPosLook.EnumFlags> relative) {
        return arrivals.defer(player, .5, 130, .5, 0, 0, x, y, z, 0, 0, relative, 100);
    }

    private void tick(long now, long nanos) {
        host.now = now;
        host.nanos = nanos;
        arrivals.tick(now, nanos);
    }

    @Test public void aTeleportIntoAnUnpreparedColumnIsHeld() {
        assertTrue(teleport(3000.5, 160, 3000.5, ABSOLUTE));
        assertTrue(arrivals.holds(player));
        assertEquals(1, host.registrations);
        assertEquals(0, host.withdrawals);
    }

    @Test public void aTeleportInPlaceKeepsTheHold() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        assertFalse(teleport(.5, 130, .5, ABSOLUTE));
        assertTrue(arrivals.holds(player));
        assertFalse(teleport(0, 0, 0, RELATIVE));
        assertTrue(arrivals.holds(player));
        assertEquals(1, host.registrations);
        assertEquals(0, host.withdrawals);
    }

    @Test public void aReadyTeleportElsewhereReplacesTheHold() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        assertFalse(teleport(40.5, 130, .5, ABSOLUTE));
        assertFalse(arrivals.holds(player));
        assertEquals(1, host.withdrawals);
    }

    @Test public void aRejectedMoveKeepsTheHold() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        arrivals.correct(() -> assertFalse(
                arrivals.defer(player, .5, 129.9, .5, 0, 0, .5, 130, .5, 0, 0, ABSOLUTE, 101)));
        assertTrue(arrivals.holds(player));
        arrivals.correct(() -> assertFalse(
                arrivals.defer(player, .5, 130, .5, 0, 0, 3000.5, 130, 3000.5, 0, 0, ABSOLUTE, 102)));
        assertTrue(arrivals.holds(player));
        assertEquals(1, host.registrations);
        assertEquals(0, host.withdrawals);
    }

    @Test public void aSlowDestinationWaitsTwoThousandTicksAndRetainsThroughInstallation() {
        host.readyAt = 2100;
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        for (int now = 101; now < 2100; now++) tick(now, now * 50_000_000L);
        assertTrue(arrivals.holds(player));
        assertTrue(host.completed.isEmpty());
        assertEquals(1, host.registrations);
        assertEquals(0, host.withdrawals);
        assertEquals(1, host.watchdogs.size());
        host.completing = target -> {
            assertEquals(new Column(187, 187), host.requests.get(player));
            assertFalse("Completion cannot recursively hold itself",
                    teleport(target.x(), target.y(), target.z(), ABSOLUTE));
        };
        tick(2100, 2100 * 50_000_000L);
        assertFalse(arrivals.holds(player));
        assertEquals(1, host.completed.size());
        assertEquals(1, host.withdrawals);
    }

    @Test public void preparationMessagesUseWallTimeAndNeverExceedOnePerSecond() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        for (int tick = 0; tick < 2000; tick++) tick(101 + tick, tick * 1_000_000L);
        assertEquals(List.of(0L, DeferredArrivals.MESSAGE_PERIOD), host.messages);
        assertTrue(arrivals.holds(player));
    }

    @Test public void replacingAHoldDoesNotResetTheMessageRateLimit() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        tick(101, 0);
        teleport(3001.5, 170, 3001.5, ABSOLUTE);
        tick(102, 1_000_000);
        assertEquals(List.of(0L), host.messages);
        tick(103, DeferredArrivals.MESSAGE_PERIOD);
        assertEquals(List.of(0L, DeferredArrivals.MESSAGE_PERIOD), host.messages);
    }

    @Test public void anUnpreparedReplacementWithdrawsTheOldRegion() {
        host.unprepared.add(new Column(188, 187));
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        teleport(3020.5, 170, 3000.5, ABSOLUTE);
        assertEquals(2, host.registrations);
        assertEquals(1, host.withdrawals);
        assertEquals(new Column(188, 187), host.requests.get(player));
        host.readyAt = 101;
        tick(101, 0);
        assertEquals(3020.5, host.completed.get(0).x(), 0);
        assertEquals(2, host.withdrawals);
    }

    @Test public void aSameColumnReplacementKeepsItsPreparedLeaseUntilTickCompletesIt() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        host.readyAt = 101;
        host.now = 101;
        assertTrue(teleport(3001.5, 170, 3001.5, ABSOLUTE));
        assertEquals(1, host.registrations);
        assertEquals(0, host.withdrawals);
        host.completing = target -> assertTrue(host.requests.containsKey(player));
        tick(102, 0);
        assertEquals(3001.5, host.completed.get(0).x(), 0);
        assertEquals(1, host.withdrawals);
    }

    @Test public void departureReleasesOnceWhetherDetectedByTickOrReportedDirectly() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        host.present = false;
        tick(101, 0);
        arrivals.departed(player);
        assertFalse(arrivals.holds(player));
        assertEquals(1, host.withdrawals);
        assertTrue(host.completed.isEmpty());
        host.present = true;
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        arrivals.departed(player);
        tick(102, 0);
        assertEquals(2, host.withdrawals);
    }

    @Test public void aFailureStaysHeldAndAllowsAnExplicitForcedMove() {
        host.failed = true;
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        for (int now = 101; now <= 2100; now++) tick(now, now * 50_000_000L);
        assertTrue(arrivals.holds(player));
        assertTrue(host.completed.isEmpty());
        assertEquals(1, host.registrations);
        assertEquals(0, host.withdrawals);
        assertEquals("Failure and long-wait diagnostics are each emitted once", 2, host.watchdogs.size());
        arrivals.teleportNow(player, new DeferredArrivals.Target(40.5, 130, .5, 0, 0));
        assertFalse(arrivals.holds(player));
        assertEquals(1, host.completed.size());
        assertEquals(1, host.withdrawals);
    }

    @Test public void completionFailureStillReleasesRetentionAndRestoresTheGuard() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        host.readyAt = 101;
        host.completing = target -> { throw new IllegalStateException("test install failure"); };
        try {
            tick(101, 0);
            fail("Expected the install failure");
        } catch (IllegalStateException expected) {
            assertEquals("test install failure", expected.getMessage());
        }
        assertEquals(1, host.withdrawals);
        assertFalse(arrivals.holds(player));
        host.readyAt = Long.MAX_VALUE;
        assertTrue(teleport(3000.5, 160, 3000.5, ABSOLUTE));
    }

    @Test public void worldCloseReleasesEveryHoldOnce() {
        teleport(3000.5, 160, 3000.5, ABSOLUTE);
        UUID other = new UUID(0, 2);
        arrivals.defer(other, .5, 130, .5, 0, 0, 3000.5, 160, 3000.5, 0, 0, ABSOLUTE, 100);
        arrivals.close();
        arrivals.close();
        assertEquals(2, host.withdrawals);
        assertFalse(arrivals.holds(player));
        assertFalse(arrivals.holds(other));
        assertTrue(host.requests.isEmpty());
    }

    @Test public void aWorldWithoutConcurrentWorkersDoesNotHoldOrRequestPreparation() {
        host.immediate = true;
        assertFalse(teleport(3000.5, 160, 3000.5, ABSOLUTE));
        assertFalse(arrivals.holds(player));
        assertEquals(0, host.registrations);
    }

    @Test public void relativeCoordinatesAndRotationBecomeAbsoluteWhenHeld() {
        assertTrue(arrivals.defer(player, .5, 130, .5, 40, -20,
                3000, 30, 3000, 5, 10, RELATIVE, 100));
        host.readyAt = 101;
        tick(101, 0);
        DeferredArrivals.Target target = host.completed.get(0);
        assertEquals(3000.5, target.x(), 0);
        assertEquals(160, target.y(), 0);
        assertEquals(3000.5, target.z(), 0);
        assertEquals(45, target.yaw(), 0);
        assertEquals(-10, target.pitch(), 0);
    }
}
