package io.github.opencubicchunks.cubicchunks.lab;
import org.junit.Test;
import static org.junit.Assert.*;
public class TestDeferredFrameReady {
    @Test public void markerNeedsStableFramesAndLaterPositiveAvailability(){
        Fake b=new Fake();DeferredFrameReady r=new DeferredFrameReady(b);
        r.begin(1);assertFalse(r.end(1,true));r.begin(1);assertFalse(r.end(1,true));assertEquals(1,b.markers);
        r.begin(1);assertFalse(r.end(1,true));assertEquals(0,b.reads);b.available=true;
        r.begin(1);assertTrue(r.end(1,true));assertEquals(1,b.reads);assertEquals(1,b.deletes);
    }
    @Test public void NewWorkInvalidatesAnAvailableOldMarkerAndChangesDuringFramePreventIssue(){
        Fake b=new Fake();DeferredFrameReady r=new DeferredFrameReady(b);r.begin(1);r.end(1,true);r.begin(1);r.end(1,true);
        b.available=true;r.begin(2);assertFalse(r.end(2,true));assertEquals(0,b.reads);assertEquals(1,b.deletes);
        r.begin(2);assertFalse(r.end(3,true));assertEquals(1,b.markers);r.close();r.close();assertEquals(1,b.deletes);
    }
    @Test public void busyOrChangedFrameRevokesAnAlreadyResolvedEndpoint(){
        Fake b=new Fake();b.available=true;DeferredFrameReady r=new DeferredFrameReady(b);
        r.begin(1);r.end(1,true);r.begin(1);r.end(1,true);r.begin(1);assertTrue(r.end(1,true));
        r.begin(1);assertFalse(r.end(1,false));r.begin(1);assertFalse(r.end(1,true));assertEquals(1,b.reads);
    }
    private static final class Fake implements DeferredFrameReady.Backend {
        int markers,reads,deletes;boolean available;
        public int create(){return 7;}public void timestamp(int q){markers++;}
        public boolean available(int q){return available;}public long result(int q){reads++;return 123;}
        public void delete(int q){deletes++;}
    }
}
