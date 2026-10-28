package io.github.opencubicchunks.cubicchunks.core.server;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class TestPendingCubeSends {
    @Test public void replacementPlayerWithTheSameEntityIdNeverSharesTheOldPendingQueue() {
        Session oldPlayer = new Session(7), replacement = new Session(7);
        assertEquals(oldPlayer, replacement); assertNotSame(oldPlayer, replacement);
        PendingCubeSends<Session, String> sends = new PendingCubeSends<>();
        sends.add(oldPlayer, "old-world-cube", 10);
        sends.add(replacement, "new-world-cube", 20);
        assertEquals(2, sends.size());
        assertFalse(sends.remove(oldPlayer, "new-world-cube"));
        sends.removePlayer(oldPlayer);
        assertSame(replacement, sends.firstPlayer());
        assertEquals("new-world-cube", sends.firstCube(replacement));
        assertEquals(1, sends.size());
    }

    @Test public void cancellationRemovesTheCubeInsideThePlayersQueue() {
        PendingCubeSends<String, String> sends = new PendingCubeSends<>();
        sends.add("alice", "old", 0); sends.add("alice", "keep", 1); sends.add("bob", "old", 2);
        assertTrue(sends.remove("alice", "old"));
        assertFalse(sends.remove("alice", "old"));
        assertEquals("keep", sends.firstCube("alice"));
        assertEquals("old", sends.firstCube("bob"));
        assertEquals(2, sends.size());
        sends.removePlayer("alice");
        assertEquals("bob", sends.firstPlayer());
        assertEquals(1, sends.size());
        sends.removePlayer("bob");
        assertTrue(sends.isEmpty());
    }

    @Test public void reschedulingKeepsAgeAndFairRotationDoesNotStarveAPlayer() {
        PendingCubeSends<String, String> sends = new PendingCubeSends<>();
        sends.add("alice", "a", 10); sends.add("alice", "b", 20); sends.add("bob", "c", 30);
        sends.add("alice", "a", 40);
        assertEquals(3, sends.size());
        assertEquals(40, sends.oldestAge(50));
        assertEquals("a", sends.firstCube("alice"));
        sends.remove("alice", "a"); sends.rotate("alice");
        assertEquals("bob", sends.firstPlayer());
        sends.remove("bob", "c");
        assertEquals("alice", sends.firstPlayer());
        assertEquals("b", sends.firstCube("alice"));
        assertEquals(30, sends.oldestAge(50));
    }

    @Test public void queuedIsNotDeliveredAndPrefixSurvivesRemovalAndRejoin() {
        ObjectArrayList<String> players = new ObjectArrayList<>();
        players.add("alice"); players.add("bob"); players.add("charlie");
        int sent = 0;
        assertFalse(PlayerCubeDelivery.isSent(players, "bob", sent));
        sent = PlayerCubeDelivery.markSent(players, "charlie", sent);
        sent = PlayerCubeDelivery.markSent(players, "alice", sent);
        assertTrue(PlayerCubeDelivery.isSent(players, "charlie", sent));
        assertTrue(PlayerCubeDelivery.isSent(players, "alice", sent));
        assertFalse(PlayerCubeDelivery.isSent(players, "bob", sent));
        assertEquals(sent, PlayerCubeDelivery.markSent(players, "alice", sent));
        sent = PlayerCubeDelivery.remove(players, "charlie", sent);
        assertTrue(PlayerCubeDelivery.isSent(players, "alice", sent));
        assertFalse(PlayerCubeDelivery.isSent(players, "bob", sent));
        players.add("charlie");
        assertFalse(PlayerCubeDelivery.isSent(players, "charlie", sent));
        sent = PlayerCubeDelivery.remove(players, "bob", sent);
        assertTrue(PlayerCubeDelivery.isSent(players, "alice", sent));
        assertEquals(1, sent);
        assertEquals(2, players.size());
    }

    @Test public void reentrantUnsubscribeUnloadsEveryCubeWhoseBaseWasAlreadyEnqueued() {
        PublicationFixture fixture = new PublicationFixture();
        fixture.firstCallback = () -> {
            fixture.unsubscribe(fixture.a);
            fixture.unsubscribe(fixture.b);
        };
        fixture.enqueueAndPublish(fixture.a, fixture.b);
        assertEquals(Arrays.asList("base:a,b", "unload:a", "unload:b"), fixture.connection);
        assertTrue(fixture.clientResident.isEmpty());
        assertTrue(fixture.pending.isEmpty());
        assertEquals(Arrays.asList("a"), fixture.watchEvents);
    }

    @Test public void reentrantUnsubscribeAndRejoinCannotNotifyTheNewQueuedSubscription() {
        PublicationFixture fixture = new PublicationFixture();
        Watch sameWatcher = fixture.watchers.get("b");
        fixture.firstCallback = () -> {
            fixture.unsubscribe(fixture.b);
            sameWatcher.players.add("alice");
            fixture.pending.add("alice", fixture.b, 900);
        };
        fixture.enqueueAndPublish(fixture.a, fixture.b);
        assertEquals(Arrays.asList("a"), fixture.watchEvents);
        assertEquals(new HashSet<>(Arrays.asList("a")), fixture.clientResident);
        assertFalse(PlayerCubeDelivery.isSent(sameWatcher.players, "alice", sameWatcher.sent));
        assertEquals(1, fixture.pending.size());
        assertEquals(100, fixture.pending.oldestAge(1000));
        fixture.enqueueAndPublish(fixture.b);
        assertEquals(Arrays.asList("a", "b"), fixture.watchEvents);
        assertTrue(fixture.pending.isEmpty());
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), fixture.clientResident);
    }

    @Test public void reentrantTrackingCallbackCannotEmitWatchAfterItsSubscriptionWasRemoved() {
        PublicationFixture fixture = new PublicationFixture();
        fixture.firstTrackingCallback = () -> {
            fixture.unsubscribe(fixture.a);
            fixture.unsubscribe(fixture.b);
        };
        fixture.enqueueAndPublish(fixture.a, fixture.b);
        assertEquals(Arrays.asList("base:a,b", "unload:a", "unload:b"), fixture.connection);
        assertTrue(fixture.watchEvents.isEmpty());
        assertTrue(fixture.clientResident.isEmpty());
        assertTrue(fixture.pending.isEmpty());
    }

    @Test public void replacementWatcherAtTheSameCoordinateCannotReceiveAnOldBatchNotification() {
        PublicationFixture fixture = new PublicationFixture();
        FakeCube replacementCube = new FakeCube("b");
        Watch replacement = new Watch(replacementCube);
        fixture.firstCallback = () -> {
            fixture.unsubscribe(fixture.b);
            fixture.watchers.put("b", replacement);
            fixture.pending.add("alice", replacementCube, 900);
        };
        fixture.enqueueAndPublish(fixture.a, fixture.b);
        assertEquals(Arrays.asList("a"), fixture.watchEvents);
        assertFalse(PlayerCubeDelivery.isSent(replacement.players, "alice", replacement.sent));
        assertSame(replacementCube, fixture.pending.firstCube("alice"));
        assertFalse(fixture.clientResident.contains("b"));
    }

    @Test public void publicationAlignmentFailsBeforeAnySentTransitionOrCallback() {
        int[] calls = {0};
        PlayerCubeDelivery.Publication<String, String, String> publication = new PlayerCubeDelivery.Publication<String, String, String>() {
            @Override public void markSent(String cube, String watcher, String player) { calls[0]++; }
            @Override public void notifySent(String cube, String watcher, String player) { calls[0]++; }
        };
        try {
            PlayerCubeDelivery.publishBatch(Arrays.asList("a", "b"), Arrays.asList("a"), "alice", publication);
            fail("Misaligned original-watcher list must not partly publish");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, calls[0]);
        }
    }

    /** Real production two-phase seam, with subscription cancellation and a base/unload client-state oracle. */
    private static final class PublicationFixture {
        final FakeCube a = new FakeCube("a"), b = new FakeCube("b");
        final Map<String, Watch> watchers = new HashMap<>();
        final PendingCubeSends<String, FakeCube> pending = new PendingCubeSends<>();
        final List<String> connection = new ArrayList<>(), watchEvents = new ArrayList<>();
        final Set<String> clientResident = new HashSet<>();
        Runnable firstCallback, firstTrackingCallback;

        PublicationFixture() {
            watchers.put("a", new Watch(a)); watchers.put("b", new Watch(b));
            pending.add("alice", a, 10); pending.add("alice", b, 20);
        }

        void unsubscribe(FakeCube cube) {
            Watch watcher = watchers.get(cube.position);
            if (PlayerCubeDelivery.isSent(watcher.players, "alice", watcher.sent)) {
                connection.add("unload:" + cube.position); clientResident.remove(cube.position);
            }
            pending.remove("alice", cube);
            watcher.sent = PlayerCubeDelivery.remove(watcher.players, "alice", watcher.sent);
        }

        void enqueueAndPublish(FakeCube... values) {
            List<FakeCube> cubes = Arrays.asList(values);
            List<Watch> original = new ArrayList<>();
            StringBuilder base = new StringBuilder("base:");
            for (FakeCube cube : cubes) {
                pending.remove("alice", cube); original.add(watchers.get(cube.position));
                if (base.length() > 5) base.append(','); base.append(cube.position);
                clientResident.add(cube.position);
            }
            connection.add(base.toString());
            PlayerCubeDelivery.publishBatch(cubes, original, "alice", new PlayerCubeDelivery.Publication<FakeCube, Watch, String>() {
                private boolean current(FakeCube cube, Watch watcher) {
                    return watcher != null && watcher.cube == cube && watchers.get(cube.position) == watcher;
                }
                @Override public void markSent(FakeCube cube, Watch watcher, String player) {
                    if (current(cube, watcher) && watcher.players.contains(player)) {
                        watcher.sent = PlayerCubeDelivery.markSent(watcher.players, player, watcher.sent);
                    }
                }
                @Override public void notifySent(FakeCube cube, Watch watcher, String player) {
                    if (!current(cube, watcher) || !PlayerCubeDelivery.isSent(watcher.players, player, watcher.sent)) return;
                    Runnable tracking = firstTrackingCallback; firstTrackingCallback = null;
                    if (tracking != null) tracking.run();
                    if (!current(cube, watcher) || !PlayerCubeDelivery.isSent(watcher.players, player, watcher.sent)) return;
                    watchEvents.add(cube.position);
                    Runnable callback = firstCallback; firstCallback = null;
                    if (callback != null) callback.run();
                }
            });
        }
    }

    private static final class FakeCube {
        final String position;
        FakeCube(String position) { this.position = position; }
    }

    private static final class Watch {
        final FakeCube cube;
        final ObjectArrayList<String> players = new ObjectArrayList<>();
        int sent;
        Watch(FakeCube cube) { this.cube = cube; players.add("alice"); players.add("bob"); }
    }

    private static final class Session {
        final int entityId;
        Session(int entityId) { this.entityId = entityId; }
        @Override public boolean equals(Object other) { return other instanceof Session && ((Session) other).entityId == entityId; }
        @Override public int hashCode() { return entityId; }
    }
}
