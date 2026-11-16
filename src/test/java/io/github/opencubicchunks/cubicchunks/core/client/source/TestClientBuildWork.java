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
package io.github.opencubicchunks.cubicchunks.core.client.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.core.server.delivery.LivePermits;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class TestClientBuildWork {

    /** A world of cubes whose loading and versions the test sets, with snapshots that count releases. */
    static final class World implements ClientBuildWork.Cubes {
        final Map<Long, long[]> cubes = new HashMap<>();
        final List<String> taken = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger snapshots = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        long counter;

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | (long) z & 0xFFFFF;
        }

        /** Loads a cube with fresh versions. */
        World load(int x, int y, int z) {
            long v = ++counter;
            cubes.put(key(x, y, z), new long[] {v, v, v});
            return this;
        }

        void unload(int x, int y, int z) {
            cubes.remove(key(x, y, z));
        }

        void changeBlocks(int x, int y, int z) {
            long[] v = cubes.get(key(x, y, z));
            v[0] = v[1] = ++counter;
        }

        void changeLight(int x, int y, int z) {
            long[] v = cubes.get(key(x, y, z));
            v[0] = v[2] = ++counter;
        }

        @Override public boolean isLoaded(int x, int y, int z) {
            return cubes.containsKey(key(x, y, z));
        }

        @Override public ICubeSnapshot snapshot(int x, int y, int z, boolean centerOnly) {
            snapshots.incrementAndGet();
            taken.add(x + " " + y + " " + z);
            AtomicBoolean once = new AtomicBoolean();
            return (ICubeSnapshot) Proxy.newProxyInstance(ICubeSnapshot.class.getClassLoader(),
                    new Class<?>[] {ICubeSnapshot.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "release":
                                if (!once.compareAndSet(false, true)) {
                                    throw new AssertionError("released twice");
                                }
                                released.incrementAndGet();
                                return null;
                            case "centerX": return x;
                            case "centerY": return y;
                            case "centerZ": return z;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            default: return null;
                        }
                    });
        }

        @Override public long version(int x, int y, int z) {
            long[] v = cubes.get(key(x, y, z));
            return v == null ? -1 : v[0];
        }

        @Override public long blockVersion(int x, int y, int z) {
            long[] v = cubes.get(key(x, y, z));
            return v == null ? -1 : v[1];
        }

        @Override public long lightVersion(int x, int y, int z) {
            long[] v = cubes.get(key(x, y, z));
            return v == null ? -1 : v[2];
        }
    }

    private final List<ClientBuildWork> pools = new ArrayList<>();

    private ClientBuildWork pool(int threads, LivePermits permits, World world) {
        ClientBuildWork pool = new ClientBuildWork(threads, permits);
        pool.bind(world);
        pools.add(pool);
        return pool;
    }

    @After
    public void stopThreads() {
        for (ClientBuildWork pool : pools) {
            pool.bind(null);
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(2);
        }
    }

    /** Polls until a task comes back, pumping as the client thread would. */
    private static IClientBuildWork.Task<?> pollOne(ClientBuildWork pool) throws InterruptedException {
        IClientBuildWork.Task<?>[] found = new IClientBuildWork.Task<?>[1];
        waitFor(() -> (found[0] = pool.poll()) != null);
        return found[0];
    }

    /** A job that waits on a latch, then returns its cube's X. */
    private static IClientBuildWork.Job<Integer> gated(CountDownLatch gate, CountDownLatch started) {
        return (snapshot, task) -> {
            started.countDown();
            gate.await();
            return snapshot.centerX();
        };
    }

    private static final IClientBuildWork.Job<Integer> X = (snapshot, task) -> snapshot.centerX();

    @Test
    public void tasksRunByPriorityThenArrival() throws Exception {
        World world = new World();
        for (int x = 0; x < 8; x++) {
            world.load(x, 0, 0);
        }
        // One thread, one permit, a lookahead of one: two tasks hold the room, the rest wait in the heap.
        ClientBuildWork pool = pool(1, new LivePermits(1), world);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        pool.submit(0, 0, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, gated(gate, started));
        pool.submit(0, 1, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        started.await();
        pool.submit(5, 2, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        pool.submit(1, 3, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        pool.submit(3, 4, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        pool.submit(1, 5, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        assertEquals(4, pool.queued());
        assertEquals(2, world.snapshots.get());
        gate.countDown();
        List<Integer> done = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            done.add((Integer) pollOne(pool).result());
        }
        assertEquals(java.util.Arrays.asList("0 0 0", "1 0 0", "3 0 0", "5 0 0", "4 0 0", "2 0 0"), world.taken);
        assertEquals(6, done.size());
        waitFor(() -> world.released.get() == 6);
    }

    @Test
    public void reprioritizingOneOrAllChangesWhatIsAdmittedNext() throws Exception {
        World world = new World();
        for (int x = 0; x < 8; x++) {
            world.load(x, 0, 0);
        }
        ClientBuildWork pool = pool(1, new LivePermits(1), world);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        pool.submit(0, 0, 0, 0, false, 0, gated(gate, started));
        pool.submit(0, 1, 0, 0, false, 0, X);
        started.await();
        IClientBuildWork.Task<Integer> a = pool.submit(10, 2, 0, 0, false, 0, X);
        IClientBuildWork.Task<Integer> b = pool.submit(20, 3, 0, 0, false, 0, X);
        IClientBuildWork.Task<Integer> c = pool.submit(30, 4, 0, 0, false, 0, X);
        pool.reprioritize(c, 5);
        assertEquals(5, c.priority());
        // As if the camera turned: farthest X first now.
        pool.reprioritizeAll(task -> -task.cubeX());
        assertEquals(-3, b.priority());
        gate.countDown();
        for (int i = 0; i < 5; i++) {
            pollOne(pool);
        }
        assertEquals(java.util.Arrays.asList("0 0 0", "1 0 0", "4 0 0", "3 0 0", "2 0 0"), world.taken);
        // An admitted or ended task is not reprioritized.
        pool.reprioritize(a, 99);
        assertEquals(-2, a.priority());
    }

    @Test
    public void cancellingAtEveryStageReleasesTheSnapshotAndNothingComesBack() throws Exception {
        World world = new World();
        for (int x = 0; x < 8; x++) {
            world.load(x, 0, 0);
        }
        ClientBuildWork pool = pool(1, new LivePermits(1), world);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sawCancel = new AtomicBoolean();
        IClientBuildWork.Task<Integer> running = pool.submit(0, 0, 0, 0, false, 0, (snapshot, task) -> {
            started.countDown();
            gate.await();
            sawCancel.set(task.cancelled());
            return 0;
        });
        IClientBuildWork.Task<Integer> admittedWaiting = pool.submit(0, 1, 0, 0, false, 0, X);
        started.await();
        IClientBuildWork.Task<Integer> queued = pool.submit(0, 2, 0, 0, false, 0, X);
        assertEquals(IClientBuildWork.ADMITTED, admittedWaiting.state());
        assertEquals(IClientBuildWork.QUEUED, queued.state());
        queued.cancel();
        admittedWaiting.cancel();
        running.cancel();
        assertEquals(IClientBuildWork.CANCELLED, queued.state());
        assertTrue(running.cancelled());
        gate.countDown();
        waitFor(() -> world.released.get() == 2);
        assertTrue(sawCancel.get());
        // The cancelled queued task never took a snapshot.
        assertEquals(2, world.snapshots.get());
        IClientBuildWork.Task<Integer> done = pool.submit(0, 3, 0, 0, false, 0, X);
        waitFor(() -> done.state() == IClientBuildWork.DONE);
        done.cancel();
        Thread.sleep(20);
        assertNull(pool.poll());
        assertEquals(world.snapshots.get(), world.released.get());
    }

    @Test
    public void aCubeThatLeavesMakesItsTaskStaleAtEveryStage() throws Exception {
        World world = new World();
        for (int x = 0; x < 8; x++) {
            world.load(x, 0, 0);
        }
        ClientBuildWork pool = pool(1, new LivePermits(1), world);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sawCancel = new AtomicBoolean();
        IClientBuildWork.Task<Integer> running = pool.submit(0, 0, 0, 0, false, IClientBuildWork.STALE_ON_CENTER,
                (snapshot, task) -> {
                    started.countDown();
                    gate.await();
                    sawCancel.set(task.cancelled());
                    return 0;
                });
        pool.submit(0, 1, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        started.await();
        IClientBuildWork.Task<Integer> queued = pool.submit(0, 2, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        world.unload(0, 0, 0);
        world.unload(2, 0, 0);
        pool.pump();
        assertEquals(IClientBuildWork.STALE, running.state());
        gate.countDown();
        IClientBuildWork.Task<?> back = pollOne(pool);
        assertEquals(1, back.cubeX());
        waitFor(() -> queued.state() == IClientBuildWork.STALE);
        assertTrue(sawCancel.get());
        Thread.sleep(20);
        assertNull(pool.poll());
        // The unloaded queued cube never took a snapshot.
        assertFalse(world.taken.contains("2 0 0"));
        waitFor(() -> world.snapshots.get() == world.released.get());
    }

    @Test
    public void theMaskSaysWhichChangesMakeAFinishedTaskStale() throws Exception {
        World world = new World().load(0, 0, 0).load(1, 0, 0).load(2, 0, 0).load(3, 0, 0).load(4, 0, 0);
        world.load(-1, 0, 0);
        ClientBuildWork pool = pool(2, new LivePermits(2), world);
        IClientBuildWork.Task<Integer> blocks = pool.submit(0, 0, 0, 0, false, IClientBuildWork.STALE_ON_BLOCKS, X);
        IClientBuildWork.Task<Integer> lightOnly = pool.submit(0, 1, 0, 0, false, IClientBuildWork.STALE_ON_BLOCKS, X);
        IClientBuildWork.Task<Integer> neighbors = pool.submit(0, 3, 0, 0, false, IClientBuildWork.STALE_ON_NEIGHBORS, X);
        IClientBuildWork.Task<Integer> center = pool.submit(0, 4, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
        waitFor(() -> blocks.state() == IClientBuildWork.DONE && lightOnly.state() == IClientBuildWork.DONE
                && neighbors.state() == IClientBuildWork.DONE && center.state() == IClientBuildWork.DONE);
        world.changeBlocks(0, 0, 0);
        // Light alone does not make a blocks-only task stale.
        world.changeLight(1, 0, 0);
        // A neighbor of 3 changes; 4's own light changes.
        world.changeBlocks(2, 0, 0);
        world.changeLight(4, 0, 0);
        List<Integer> back = new ArrayList<>();
        for (IClientBuildWork.Task<?> task = pool.poll(); task != null; task = pool.poll()) {
            back.add(task.cubeX());
        }
        assertEquals(Collections.singletonList(1), back);
        assertEquals(IClientBuildWork.STALE, blocks.state());
        assertEquals(IClientBuildWork.STALE, neighbors.state());
        assertEquals(IClientBuildWork.STALE, center.state());
        assertEquals(world.snapshots.get(), world.released.get());
    }

    @Test
    public void theSnapshotVersionIsTheCentersWhenAdmitted() throws Exception {
        World world = new World().load(0, 0, 0);
        ClientBuildWork pool = pool(1, new LivePermits(1), world);
        IClientBuildWork.Task<Integer> task = pool.submit(0, 0, 0, 0, true, 0, X);
        assertEquals(world.version(0, 0, 0), task.snapshotVersion());
        assertSame(task, pollOne(pool));
    }

    @Test
    public void aFailingJobComesBackWithWhatItThrewAndReleasesItsSnapshot() throws Exception {
        World world = new World().load(0, 0, 0);
        ClientBuildWork pool = pool(1, new LivePermits(1), world);
        IllegalStateException thrown = new IllegalStateException("mesh");
        pool.submit(0, 0, 0, 0, false, 0, (snapshot, task) -> {
            throw thrown;
        });
        IClientBuildWork.Task<?> back = pollOne(pool);
        assertEquals(IClientBuildWork.FAILED, back.state());
        assertSame(thrown, back.failure());
        assertEquals(1, world.released.get());
    }

    @Test
    public void aWorldThatGoesDropsEverythingStopsTheThreadsAndReleasesEverySnapshot() throws Exception {
        World world = new World();
        for (int x = 0; x < 16; x++) {
            world.load(x, 0, 0);
        }
        ClientBuildWork pool = pool(2, new LivePermits(2), world);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        List<IClientBuildWork.Task<?>> tasks = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            tasks.add(pool.submit(x, x, 0, 0, false, 0, gated(never, started)));
        }
        started.await();
        assertEquals(12, pool.queued());
        assertEquals(2, pool.liveThreads());
        pool.bind(null);
        // Counted as logged: two threads told to stop, all sixteen tasks dropped, two running interrupted.
        org.junit.Assert.assertArrayEquals(new int[] {2, 16, 2}, pool.lastStop);
        for (IClientBuildWork.Task<?> task : tasks) {
            assertEquals(IClientBuildWork.DROPPED, task.state());
        }
        waitFor(() -> pool.liveThreads() == 0);
        waitFor(() -> world.released.get() == world.snapshots.get());
        assertEquals(4, world.snapshots.get());
        assertNull(pool.poll());
        // With no world a task is dropped at once.
        assertEquals(IClientBuildWork.DROPPED, pool.submit(0, 0, 0, 0, false, 0, X).state());
    }

    @Test
    public void noMoreSnapshotsAreHeldThanThePermitsAndOneTaskAThread() throws Exception {
        World world = new World();
        for (int x = 0; x < 64; x++) {
            world.load(x, 0, 0);
        }
        ClientBuildWork pool = pool(3, new LivePermits(2), world);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        for (int x = 0; x < 64; x++) {
            pool.submit(x, x, 0, 0, false, 0, gated(gate, started));
        }
        started.await();
        pool.pump();
        assertEquals(5, world.snapshots.get());
        assertEquals(59, pool.queued());
        gate.countDown();
        int back = 0;
        while (back < 64) {
            pollOne(pool);
            back++;
            int taken = world.snapshots.get();
            int released = world.released.get();
            assertTrue("held " + (taken - released) + " after " + back + " back, queued " + pool.queued() + " admitted " + pool.admitted(), taken - released <= 5);
        }
        assertEquals(64, world.snapshots.get());
    }

    @Test
    public void thePreviewPoolAndTheBuildPoolShareOneBudget() throws Exception {
        LivePermits shared = new LivePermits(1);
        World world = new World().load(0, 0, 0);
        ClientBuildWork builds = pool(2, shared, world);
        ClientPreviewWork previews = new ClientPreviewWork(2, shared);
        ICubicTerrainPreview preview = (ICubicTerrainPreview) Proxy.newProxyInstance(
                ICubicTerrainPreview.class.getClassLoader(), new Class<?>[] {ICubicTerrainPreview.class},
                (proxy, method, args) -> method.getName().equals("createTerrainPreviewSampler")
                        ? Proxy.newProxyInstance(ICubicTerrainPreview.Sampler.class.getClassLoader(),
                                new Class<?>[] {ICubicTerrainPreview.Sampler.class}, (p, m, a) -> null)
                        : null);
        previews.bind(preview);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            builds.submit(0, 0, 0, 0, false, 0, gated(gate, started));
            started.await();
            AtomicBoolean previewRan = new AtomicBoolean();
            previews.submit(0, (sampler, task) -> previewRan.set(true));
            Thread.sleep(200);
            // The build job holds the one permit: the preview job waits for it.
            assertFalse(previewRan.get());
            assertEquals(1, shared.active());
            gate.countDown();
            waitFor(previewRan::get);
            assertNotNull(pollOne(builds));
        } finally {
            previews.bind(null);
        }
    }

    @Test
    public void theHeapKeepsOrderUnderRandomChanges() {
        Random random = new Random(8675309);
        ClientBuildWork.Heap heap = new ClientBuildWork.Heap();
        List<ClientBuildWork.BuildTask<?>> live = new ArrayList<>();
        ClientBuildWork owner = new ClientBuildWork(1, new LivePermits(1));
        for (int i = 0; i < 2000; i++) {
            ClientBuildWork.BuildTask<Integer> task = new ClientBuildWork.BuildTask<>(owner, random.nextInt(100), i,
                    0, 0, 0, false, 0, X);
            heap.add(task);
            live.add(task);
        }
        for (int i = 0; i < 500; i++) {
            ClientBuildWork.BuildTask<?> task = live.get(random.nextInt(live.size()));
            task.priority = random.nextInt(100);
            heap.update(task.heapIndex);
        }
        for (int i = 0; i < 300; i++) {
            ClientBuildWork.BuildTask<?> task = live.remove(random.nextInt(live.size()));
            heap.remove(task.heapIndex);
            assertEquals(-1, task.heapIndex);
        }
        for (ClientBuildWork.BuildTask<?> task : live) {
            task.priority = random.nextInt(100);
        }
        heap.rebuild();
        live.sort((a, b) -> a.priority != b.priority ? Long.compare(a.priority, b.priority) : Long.compare(a.order, b.order));
        for (ClientBuildWork.BuildTask<?> expected : live) {
            assertSame(expected, heap.poll());
        }
        assertEquals(0, heap.size());
    }

    @Test
    public void underArrivalsAndDeparturesTheAdmittedStayBounded() throws Exception {
        World world = new World();
        int cubes = 3000;
        for (int x = 0; x < cubes; x++) {
            world.load(x, 0, 0);
        }
        ClientBuildWork pool = pool(4, new LivePermits(4), world);
        Random random = new Random(42);
        int maxAdmitted = 0;
        int back = 0;
        for (int x = 0; x < cubes; x++) {
            pool.submit(random.nextInt(1000), x, 0, 0, false, IClientBuildWork.STALE_ON_CENTER, X);
            if (x % 7 == 0) {
                world.unload(random.nextInt(cubes), 0, 0);
            }
            if (x % 3 == 0 && world.isLoaded(x, 0, 0)) {
                world.changeLight(x, 0, 0);
            }
            for (IClientBuildWork.Task<?> task = pool.poll(); task != null; task = pool.poll()) {
                back++;
            }
            maxAdmitted = Math.max(maxAdmitted, pool.admitted());
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((pool.queued() > 0 || pool.admitted() > 0 || pool.finished() > 0) && System.nanoTime() < deadline) {
            for (IClientBuildWork.Task<?> task = pool.poll(); task != null; task = pool.poll()) {
                back++;
            }
            maxAdmitted = Math.max(maxAdmitted, pool.admitted());
            Thread.sleep(1);
        }
        assertEquals("queued left", 0, pool.queued());
        assertEquals("admitted left", 0, pool.admitted());
        assertEquals("finished left", 0, pool.finished());
        assertTrue("most admitted " + maxAdmitted, maxAdmitted <= 8);
        assertTrue(back > 0);
        waitFor(() -> world.snapshots.get() == world.released.get());
    }

    @Test
    public void theConfiguredSize() {
        assertEquals(2, ClientBuildWork.size(-1, 8));
        assertEquals(1, ClientBuildWork.size(-1, 2));
        assertEquals(4, ClientBuildWork.size(-1, 32));
        assertEquals(6, ClientBuildWork.size(6, 2));
    }
}
