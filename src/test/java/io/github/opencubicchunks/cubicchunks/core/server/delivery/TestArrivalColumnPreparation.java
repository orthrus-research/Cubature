package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.worldgen.IRetainedColumnPreparation;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.*;

public class TestArrivalColumnPreparation {
    private static final UUID FIRST = new UUID(0, 1), SECOND = new UUID(0, 2), THIRD = new UUID(0, 3);

    /** Every submission and completion is controlled by the test, without sleeping or helper threads. */
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void execute(Runnable command) { pending.add(command); }
        void runNext() { pending.remove().run(); }
        void runAll() { while (!pending.isEmpty()) runNext(); }
    }

    private record Position(int x, int z) { }

    private static final class Lease implements IRetainedColumnPreparation.ColumnLease {
        int closes;
        @Override public void close() { closes++; }
    }

    private static final class Generator implements IRetainedColumnPreparation {
        final List<Position> built = new ArrayList<>();
        final List<Lease> leases = new ArrayList<>();
        boolean fail, noLease;

        @Override public ColumnLease prepareRetainedColumn(int columnX, int columnZ) {
            built.add(new Position(columnX, columnZ));
            if (fail) throw new IllegalStateException("test preparation failed");
            if (noLease) return null;
            Lease lease = new Lease();
            leases.add(lease);
            return lease;
        }
    }

    @Test public void statusNeverSchedulesUnknownColumns() {
        ManualExecutor executor = new ManualExecutor();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, new Generator(), 1);
        ArrivalColumnPreparation.Status status = queue.status(1, 2);
        assertEquals(ArrivalColumnPreparation.State.PENDING, status.state());
        assertTrue(status.detail().contains("not requested"));
        assertEquals(0, queue.retained());
        assertTrue(executor.pending.isEmpty());
    }

    @Test public void twoPlayersShareOneRetainedSuccessUntilBothRelease() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        queue.retain(FIRST, 1, 2);
        queue.retain(SECOND, 1, 2);
        assertEquals(1, executor.pending.size());
        assertEquals(1, queue.running());
        executor.runAll();
        assertEquals(List.of(new Position(1, 2)), generator.built);
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        queue.release(FIRST);
        assertEquals(0, generator.leases.get(0).closes);
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        queue.release(SECOND);
        queue.release(SECOND);
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(0, queue.retained());
    }

    @Test public void capacityWaitsForReleaseInsteadOfEvictingAnOwnedSuccess() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        executor.runAll();
        queue.retain(SECOND, 3, 4);
        assertEquals(1, queue.retained());
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        assertEquals(ArrivalColumnPreparation.State.PENDING, queue.status(3, 4).state());
        assertTrue(queue.status(3, 4).detail().contains("capacity"));
        assertTrue(executor.pending.isEmpty());
        queue.release(FIRST);
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(1, executor.pending.size());
        assertEquals(1, queue.retained());
        executor.runAll();
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(3, 4).state());
        queue.close();
        assertEquals(1, generator.leases.get(1).closes);
    }

    @Test public void anAbandonedJobKeepsItsSlotAndClosesItsLateResultOnce() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        queue.release(FIRST);
        queue.retain(SECOND, 3, 4);
        assertEquals(1, queue.retained());
        assertEquals(1, executor.pending.size());
        executor.runNext();
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(1, queue.retained());
        assertEquals(1, queue.running());
        assertEquals(1, executor.pending.size());
        executor.runNext();
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(3, 4).state());
        queue.release(SECOND);
        queue.close();
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(1, generator.leases.get(1).closes);
    }

    @Test public void anotherOwnerCanReattachToAnAbandonedJobWithoutDuplicatingIt() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, -1, -2);
        queue.release(FIRST);
        queue.retain(SECOND, -1, -2);
        assertEquals(1, executor.pending.size());
        executor.runAll();
        assertEquals(1, generator.built.size());
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(-1, -2).state());
        assertEquals(0, generator.leases.get(0).closes);
        queue.release(SECOND);
        assertEquals(1, generator.leases.get(0).closes);
    }

    @Test public void failureStaysLatchedUntilAllOwnersLeaveAndAnExplicitRequestRetries() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        generator.fail = true;
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        executor.runAll();
        for (int tick = 0; tick < 2000; tick++)
            assertEquals(ArrivalColumnPreparation.State.FAILED, queue.status(1, 2).state());
        assertTrue(queue.status(1, 2).detail().contains("IllegalStateException"));
        queue.retain(SECOND, 1, 2);
        assertEquals(1, generator.built.size());
        assertTrue(executor.pending.isEmpty());
        queue.release(FIRST);
        assertEquals(ArrivalColumnPreparation.State.FAILED, queue.status(1, 2).state());
        queue.release(SECOND);
        generator.fail = false;
        queue.retain(FIRST, 1, 2);
        assertEquals(1, executor.pending.size());
        executor.runAll();
        assertEquals(2, generator.built.size());
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        queue.close();
    }

    @Test public void anExistingOwnersExplicitRetryStartsOneAttemptForEveryOwnerOfTheFailedKey() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        generator.fail = true;
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        executor.runAll();
        queue.retain(SECOND, 1, 2);
        assertTrue("A new owner joining a failed key does not retry it", executor.pending.isEmpty());
        generator.fail = false;
        queue.retain(FIRST, 1, 2);
        queue.retain(SECOND, 1, 2);
        queue.retain(FIRST, 1, 2);
        assertEquals(1, executor.pending.size());
        assertEquals(1, queue.retained());
        assertEquals(1, queue.running());
        assertEquals(ArrivalColumnPreparation.State.PENDING, queue.status(1, 2).state());
        executor.runAll();
        assertEquals(2, generator.built.size());
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        queue.release(FIRST);
        assertEquals("Retry preserved the other owner's retention", 0, generator.leases.get(0).closes);
        queue.retain(SECOND, 1, 2);
        assertTrue("A ready request remains idempotent", executor.pending.isEmpty());
        queue.release(SECOND);
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(0, queue.retained());
    }

    @Test public void rejectedSubmissionIsFailedAndNeverRunsOnTheRequestingThread() {
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(command -> {
            throw new RejectedExecutionException("test executor stopped");
        }, generator, 1);
        queue.retain(FIRST, 1, 2);
        assertEquals(ArrivalColumnPreparation.State.FAILED, queue.status(1, 2).state());
        assertEquals(0, queue.running());
        assertTrue(generator.built.isEmpty());
        queue.close();
        assertEquals(0, queue.retained());
    }

    @Test public void inlineCompletionAndReadmissionKeepCapacityAndLeaseOwnership() {
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(Runnable::run, generator, 1);
        queue.retain(FIRST, 1, 2);
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        assertEquals(0, queue.running());
        queue.retain(SECOND, 3, 4);
        assertEquals(ArrivalColumnPreparation.State.PENDING, queue.status(3, 4).state());
        assertEquals(1, generator.built.size());
        queue.release(FIRST);
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(3, 4).state());
        assertEquals(1, queue.retained());
        assertEquals(0, queue.running());
        assertEquals(2, generator.built.size());
        queue.close();
        assertEquals(1, generator.leases.get(1).closes);
    }

    @Test public void closeReleasesReadyAndLateResultsAndAdmitsNoNewRequests() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 2);
        queue.retain(FIRST, 1, 2);
        executor.runNext();
        queue.retain(SECOND, 3, 4);
        queue.close();
        queue.close();
        assertEquals(0, queue.retained());
        assertEquals(0, queue.running());
        assertEquals(1, generator.leases.get(0).closes);
        queue.retain(THIRD, 5, 6);
        assertEquals(1, executor.pending.size());
        assertEquals(ArrivalColumnPreparation.State.FAILED, queue.status(5, 6).state());
        executor.runAll();
        assertEquals(2, generator.built.size());
        assertEquals(1, generator.leases.get(1).closes);
        assertEquals(0, queue.retained());
        assertEquals(0, queue.running());
    }

    @Test public void closeForgetsQueuedTasksThatTheExecutorDiscardsWithoutRunning() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        assertEquals(1, queue.running());
        queue.close();
        executor.pending.clear();
        assertEquals(0, queue.retained());
        assertEquals(0, queue.running());
        assertTrue(generator.built.isEmpty());
        assertTrue(generator.leases.isEmpty());
        queue.close();
    }

    @Test public void aWorkerReturningAfterCloseReleasesItsLateLeaseOnce() {
        ManualExecutor executor = new ManualExecutor();
        Lease lease = new Lease();
        ArrivalColumnPreparation[] owner = new ArrivalColumnPreparation[1];
        owner[0] = new ArrivalColumnPreparation(executor, (x, z) -> {
            owner[0].close();
            assertEquals(0, owner[0].retained());
            assertEquals(0, owner[0].running());
            return lease;
        }, 1);
        owner[0].retain(FIRST, 1, 2);
        executor.runNext();
        assertEquals(1, lease.closes);
        assertEquals(0, owner[0].retained());
        owner[0].close();
        assertEquals(1, lease.closes);
    }

    @Test public void replacingAnOwnersDestinationReleasesOnlyItsPriorLease() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        executor.runAll();
        queue.retain(FIRST, 3, 4);
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(1, executor.pending.size());
        executor.runAll();
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(3, 4).state());
        queue.release(FIRST);
        assertEquals(1, generator.leases.get(1).closes);
    }

    @Test public void replacingOneOfTwoOwnersKeepsTheirSharedLeaseAndWaitsForCapacity() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        queue.retain(SECOND, 1, 2);
        executor.runAll();
        queue.retain(FIRST, 3, 4);
        assertEquals(0, generator.leases.get(0).closes);
        assertEquals(1, generator.built.size());
        assertEquals(1, queue.retained());
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(1, 2).state());
        assertEquals(ArrivalColumnPreparation.State.PENDING, queue.status(3, 4).state());
        assertTrue(executor.pending.isEmpty());
        queue.release(SECOND);
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(1, executor.pending.size());
        executor.runAll();
        assertEquals(List.of(new Position(1, 2), new Position(3, 4)), generator.built);
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(3, 4).state());
        queue.close();
        assertEquals(1, generator.leases.get(0).closes);
        assertEquals(1, generator.leases.get(1).closes);
    }

    @Test public void signedCoordinatePairsDoNotAlias() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 2);
        queue.retain(FIRST, -1, Integer.MIN_VALUE);
        queue.retain(SECOND, Integer.MIN_VALUE, Integer.MAX_VALUE);
        executor.runAll();
        assertEquals(List.of(new Position(-1, Integer.MIN_VALUE), new Position(Integer.MIN_VALUE, Integer.MAX_VALUE)),
                generator.built);
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(-1, Integer.MIN_VALUE).state());
        assertEquals(ArrivalColumnPreparation.State.READY, queue.status(Integer.MIN_VALUE, Integer.MAX_VALUE).state());
        queue.close();
    }

    @Test public void aMissingLeaseFailsInsteadOfAdvertisingReadiness() {
        ManualExecutor executor = new ManualExecutor();
        Generator generator = new Generator();
        generator.noLease = true;
        ArrivalColumnPreparation queue = new ArrivalColumnPreparation(executor, generator, 1);
        queue.retain(FIRST, 1, 2);
        executor.runAll();
        assertEquals(ArrivalColumnPreparation.State.FAILED, queue.status(1, 2).state());
        assertTrue(queue.status(1, 2).detail().contains("NullPointerException"));
        queue.close();
    }
}
