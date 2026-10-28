package io.github.opencubicchunks.cubicchunks.lab;
import org.junit.Test;
import static org.junit.Assert.*;
public class TestRenderTaskLedger {
    @Test public void workerMayFinishBeforeOfferReturnsWithoutCreatingAnIdleGap(){
        RenderTaskLedger l=new RenderTaskLedger(4);Object t=new Object(),c=new Object();long token=l.offer(t,c);
        assertFalse(l.snapshot().idle());l.start(t,c);l.returned(t,true,false);assertFalse(l.snapshot().idle());
        l.offered(t,token,true);assertTrue(l.snapshot().idle());
    }
    @Test public void cancellationCannotHideAnActiveWorker(){
        RenderTaskLedger l=new RenderTaskLedger(4);Object t=new Object(),c=new Object();long token=l.offer(t,c);
        l.offered(t,token,true);l.start(t,c);l.cancel(t);assertEquals(1,l.snapshot().active);
        assertFalse(l.snapshot().idle());l.returned(t,true,false);assertTrue(l.snapshot().idle());
    }
    @Test public void rejectionAndQueuedCancellationConserveEntries(){
        RenderTaskLedger l=new RenderTaskLedger(4);Object a=new Object(),b=new Object(),c=new Object();
        long x=l.offer(a,c);l.offered(a,x,false);long y=l.offer(b,c);l.cancel(b);l.offered(b,y,true);assertTrue(l.snapshot().idle());
    }
    @Test public void skippedChunkRequiresAReplacementAndOverflowFailsClosed(){
        RenderTaskLedger l=new RenderTaskLedger(1);Object c=new Object(),a=new Object(),b=new Object();
        l.start(a,c);l.returned(a,true,true);assertTrue(l.skipped(c));l.start(b,c);l.returned(b,true,false);assertFalse(l.skipped(c));
        l.offer(a,c);assertEquals(0,l.offer(b,c));assertFalse(l.snapshot().idle());assertEquals(1,l.snapshot().overflow);
    }
    @Test public void workerFailureRemainsUnqualifiedAfterItsEntryDrains(){
        RenderTaskLedger l=new RenderTaskLedger(1);Object t=new Object();l.start(t,new Object());l.returned(t,false,false);
        assertEquals(0,l.snapshot().active);assertFalse(l.snapshot().idle());assertEquals(1,l.snapshot().errors);
    }
}
