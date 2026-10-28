package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import org.junit.Test;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Actual execution/registration ownership with fake world keys; no Minecraft dimensions or timing sleeps. */
public class TestAsyncTaskQuiescence {
    private static final class ManualExecutor extends ThreadPoolExecutor {
        ManualExecutor(){super(1,1,1,TimeUnit.SECONDS,new LinkedBlockingQueue<>());}
        @Override public void execute(Runnable task){getQueue().add(task);}
        void runNext(){Runnable next=getQueue().poll();assertNotNull(next);next.run();}
    }
    private static final class Task extends AsyncIOProvider<String>{
        final Runnable read;int publications;
        Task(Runnable read){this.read=read;}
        @Override public void run(){try{read.run();}finally{finishAsync();}}
        @Override void runSynchronousPart(){publications++;runCallbacks();}
        @Override String get(){return "saved";}
    }
    private static List<AsyncTaskQuiescence.Registration> select(Map<String,Task> tasks,ManualExecutor executor){
        List<AsyncTaskQuiescence.Registration> owned=new ArrayList<>();
        AsyncTaskQuiescence.select(owned,tasks,key->key.startsWith("A"),executor);return owned;
    }
    @Test public void closingAReleasesOnlyItsWaitingAndQueuedTasksAndBStillCompletes()throws Exception{
        ManualExecutor executor=new ManualExecutor();Map<String,Task> tasks=new ConcurrentHashMap<>();
        AtomicInteger releasedA=new AtomicInteger(),releasedB=new AtomicInteger(),callbackB=new AtomicInteger();
        Task waiting=new Task(()->fail("Waiting A must cancel")),queued=new Task(()->fail("Queued A must cancel")),b=new Task(()->{});
        for(Task task:new Task[]{waiting,queued,b})task.prepareExecution(executor,null);
        waiting.setCleanup(releasedA::incrementAndGet);queued.setCleanup(releasedA::incrementAndGet);b.setCleanup(releasedB::incrementAndGet);
        tasks.put("A-waiting",waiting);tasks.put("A-queued",queued);tasks.put("B",b);queued.enqueue();b.enqueue();b.addCallback(value->callbackB.incrementAndGet());
        AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),TimeUnit.SECONDS.toNanos(1));
        assertEquals(2,releasedA.get());assertEquals(0,releasedB.get());assertEquals(1,tasks.size());assertSame(b,tasks.get("B"));
        assertFalse(executor.isShutdown());assertEquals(1,executor.getQueue().size());executor.runNext();
        AsyncTaskCompletion.completeForSynchronousLoad(tasks,"B",b);
        assertEquals(1,b.publications);assertEquals(1,callbackB.get());assertEquals(1,releasedB.get());assertTrue(tasks.isEmpty());
    }
    @Test public void timeoutLeavesRunningAAndItsPinOwnedUntilActualStopAndRetry()throws Exception{
        ManualExecutor executor=new ManualExecutor();Map<String,Task> tasks=new ConcurrentHashMap<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger cleanup=new AtomicInteger();
        Task a=new Task(()->{entered.countDown();await(release);}),b=new Task(()->{});
        a.prepareExecution(executor,null);b.prepareExecution(executor,null);a.setCleanup(cleanup::incrementAndGet);
        tasks.put("A",a);tasks.put("B",b);a.enqueue();Thread worker=new Thread(executor::runNext,"scoped-close-timeout");worker.setDaemon(true);worker.start();
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),0);fail("Live reader must keep storage open");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("storage remains open"));}
            assertSame(a,tasks.get("A"));assertSame(b,tasks.get("B"));assertEquals(0,cleanup.get());
            release.countDown();worker.join(5000);assertFalse(worker.isAlive());
            AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),TimeUnit.SECONDS.toNanos(1));
            assertFalse(tasks.containsKey("A"));assertSame(b,tasks.get("B"));assertEquals(1,cleanup.get());
        }finally{release.countDown();worker.interrupt();worker.join(1000);}
    }
    @Test public void interruptionPreservesStatusAndLiveReaderOwnership()throws Exception{
        ManualExecutor executor=new ManualExecutor();Map<String,Task> tasks=new ConcurrentHashMap<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger cleanup=new AtomicInteger();
        Task a=new Task(()->{entered.countDown();await(release);});a.prepareExecution(executor,null);a.setCleanup(cleanup::incrementAndGet);tasks.put("A",a);a.enqueue();
        Thread worker=new Thread(executor::runNext,"scoped-close-interrupt");worker.setDaemon(true);worker.start();
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));Thread.currentThread().interrupt();
            try{AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),TimeUnit.SECONDS.toNanos(1));fail("Interrupted close must fail");}
            catch(InterruptedIOException expected){assertTrue(Thread.currentThread().isInterrupted());}
            finally{Thread.interrupted();}
            assertSame(a,tasks.get("A"));assertEquals(0,cleanup.get());
            release.countDown();worker.join(5000);AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),TimeUnit.SECONDS.toNanos(1));
            assertEquals(1,cleanup.get());assertTrue(tasks.isEmpty());
        }finally{Thread.interrupted();release.countDown();worker.interrupt();worker.join(1000);}
    }
    @Test public void closeFromOwnDecoderFailsBeforeStorageDisposal()throws Exception{
        ManualExecutor executor=new ManualExecutor();Map<String,Task> tasks=new ConcurrentHashMap<>();AtomicInteger cleanup=new AtomicInteger();
        Task a=new Task(()->{
            try{AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),TimeUnit.SECONDS.toNanos(1));fail("Self close must not wait on its decoder");}
            catch(IOException expected){assertTrue(expected.getCause()instanceof IllegalStateException);}
        });
        a.prepareExecution(executor,null);a.setCleanup(cleanup::incrementAndGet);tasks.put("A",a);a.runOrAwait(executor);
        assertEquals(0,cleanup.get());assertSame(a,tasks.get("A"));
        AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),TimeUnit.SECONDS.toNanos(1));assertEquals(1,cleanup.get());assertTrue(tasks.isEmpty());
    }
    @Test public void lateStopCanRetireOnlyMetadataWithoutAnotherOwnerTick()throws Exception{
        ManualExecutor executor=new ManualExecutor();Map<String,Task> tasks=new ConcurrentHashMap<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger cleanup=new AtomicInteger();
        Task a=new Task(()->{entered.countDown();await(release);}),b=new Task(()->{});
        a.setCleanup(cleanup::incrementAndGet);a.prepareExecution(executor,null,()->{
            assertTrue(a.isExecutionStopped());tasks.remove("A",a);a.cleanup();
        });
        b.prepareExecution(executor,null);tasks.put("A",a);tasks.put("B",b);a.enqueue();
        Thread worker=new Thread(executor::runNext,"scoped-close-late-stop");worker.setDaemon(true);worker.start();
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),0);fail("Reader must still be running");}catch(IOException expected){}
            assertSame(a,tasks.get("A"));assertEquals(0,cleanup.get());release.countDown();worker.join(5000);assertFalse(worker.isAlive());
            assertFalse(tasks.containsKey("A"));assertSame(b,tasks.get("B"));assertEquals(1,cleanup.get());assertEquals(0,a.publications);
            AsyncTaskQuiescence.cancelAndAwait(select(tasks,executor),TimeUnit.SECONDS.toNanos(1));assertEquals(1,cleanup.get());
        }finally{release.countDown();worker.interrupt();worker.join(1000);}
    }
    @Test public void admissionTombstonesUseWorldIdentityEvenWhenWorldsCompareEqual(){
        Object a=new String("world"),b=new String("world");WeakWorldClosures<Object> closures=new WeakWorldClosures<>();
        assertEquals(a,b);closures.close(a);closures.close(a);assertTrue(closures.isClosed(a));assertFalse(closures.isClosed(b));
    }
    @Test public void stopAndPurgeBeforeRetirementPublicationCannotLeaveAStrongRecord(){
        Task task=new Task(()->{});AtomicInteger cleanup=new AtomicInteger();Object record=new Object();
        task.setCleanup(cleanup::incrementAndGet);
        ConcurrentLinkedQueue<Object> queue=new ConcurrentLinkedQueue<Object>(){
            @Override public boolean add(Object value){
                // Deterministically model a worker stopping and purging after retirement was
                // claimed but before the producer publishes its record.
                task.finished=true;task.cleanup();remove(value);
                return super.add(value);
            }
        };
        AsyncTaskRetirement.enqueue(task,queue,record,task::cleanup);
        assertTrue(queue.isEmpty());assertEquals(1,cleanup.get());
        AsyncTaskRetirement.enqueue(task,queue,record,task::cleanup);
        assertTrue(queue.isEmpty());assertEquals(1,cleanup.get());
    }
    @Test public void closeInsideNativeCallbackCannotPoisonAdmissionsOrReleaseOriginalOwnership()throws Exception{
        ManualExecutor executor=new ManualExecutor();Map<String,Task> tasks=new ConcurrentHashMap<>();
        WeakWorldClosures<Object> closures=new WeakWorldClosures<>();Object world=new Object();
        AtomicInteger cleanup=new AtomicInteger(),callbacks=new AtomicInteger();Task task=new Task(()->{});
        task.prepareExecution(executor,null);task.setCleanup(cleanup::incrementAndGet);tasks.put("A",task);task.runOrAwait(executor);
        task.addCallback(value->{
            assertTrue(task.isExecutionStopped());assertTrue(task.isCompleting());
            try{
                List<AsyncTaskQuiescence.Registration> owned=select(tasks,executor);
                AsyncTaskQuiescence.checkNotPublishing(owned);
                closures.close(world);
                AsyncTaskQuiescence.cancelAndAwait(owned,TimeUnit.SECONDS.toNanos(1));
                fail("Native publication must finish before close");
            }catch(IOException expected){assertTrue(expected.getMessage().contains("owner native publication"));}
            assertFalse(closures.isClosed(world));assertSame(task,tasks.get("A"));assertEquals(0,cleanup.get());callbacks.incrementAndGet();
        });
        task.addCallback(value->callbacks.incrementAndGet());
        AsyncTaskCompletion.completeForSynchronousLoad(tasks,"A",task);
        assertEquals(2,callbacks.get());assertEquals(1,task.publications);assertEquals(1,cleanup.get());assertTrue(tasks.isEmpty());
        List<AsyncTaskQuiescence.Registration> retry=select(tasks,executor);AsyncTaskQuiescence.checkNotPublishing(retry);closures.close(world);
        AsyncTaskQuiescence.cancelAndAwait(retry,TimeUnit.SECONDS.toNanos(1));assertTrue(closures.isClosed(world));assertEquals(1,cleanup.get());
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Reader latch timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}
}
