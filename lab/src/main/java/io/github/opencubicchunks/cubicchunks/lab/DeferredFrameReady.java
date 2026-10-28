package io.github.opencubicchunks.cubicchunks.lab;

/** One owner-context timestamp marker, queried only on subsequent frames and after positive availability. */
final class DeferredFrameReady {
    interface Backend { int create();void timestamp(int query);boolean available(int query);long result(int query);void delete(int query); }
    private final Backend backend;
    private int query,stableFrames;private long frame,issuedFrame,beginEpoch,lastEpoch=Long.MIN_VALUE,markerEpoch;
    private boolean closed,ready;private long issued,resolved,invalidated,gpuTimestamp;
    DeferredFrameReady(Backend backend){this.backend=backend;}
    void begin(long epoch){frame++;beginEpoch=epoch;}
    boolean end(long epoch,boolean idle){
        if(closed)return false;
        if(!idle||epoch!=beginEpoch||epoch!=lastEpoch){
            discard();ready=false;stableFrames=idle&&epoch==beginEpoch?1:0;lastEpoch=epoch;return false;
        }
        stableFrames++;
        if(ready)return true;
        if(query!=0){
            if(markerEpoch!=epoch){discard();return false;}
            if(frame>issuedFrame&&backend.available(query)){
                gpuTimestamp=backend.result(query);backend.delete(query);query=0;resolved++;ready=true;return true;
            }
        }else if(stableFrames>=2){
            query=backend.create();if(query==0)throw new IllegalStateException("GPU marker allocation returned zero");
            backend.timestamp(query);issuedFrame=frame;markerEpoch=epoch;issued++;
        }
        return false;
    }
    private void discard(){if(query!=0){backend.delete(query);query=0;invalidated++;}}
    void close(){if(!closed){discard();closed=true;}}
    int stableFrames(){return stableFrames;}long issued(){return issued;}long resolved(){return resolved;}
    long invalidated(){return invalidated;}long gpuTimestamp(){return gpuTimestamp;}
}
