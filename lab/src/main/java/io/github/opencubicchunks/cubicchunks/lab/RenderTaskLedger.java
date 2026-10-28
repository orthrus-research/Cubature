package io.github.opencubicchunks.cubicchunks.lab;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional bounded lifecycle accounting. Entries precede queue publication, including the take/start gap. */
final class RenderTaskLedger {
    private final int capacity;
    private final IdentityHashMap<Object, Task> tasks = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> skippedChunks = new IdentityHashMap<>();
    private long epoch, nextToken, offers, accepted, rejected, inline, starts, returns, cancelled, errors, overflow;
    RenderTaskLedger(int capacity) { if(capacity<1)throw new IllegalArgumentException();this.capacity=capacity; }
    synchronized long offer(Object task,Object chunk) {
        epoch++;
        if(tasks.containsKey(task)){errors++;return 0;}
        if(tasks.size()>=capacity){overflow++;return 0;}
        long token=++nextToken;tasks.put(task,new Task(chunk,token));offers++;return token;
    }
    synchronized void offered(Object task,long token,boolean success) {
        Task t=tasks.get(task);
        if(token==0)return;
        if(t==null||t.token!=token){errors++;epoch++;return;}
        t.offerResolved=true;t.accepted=success;if(success)accepted++;else rejected++;
        epoch++;removeIfDone(task,t);
    }
    synchronized void start(Object task,Object chunk) {
        Task t=tasks.get(task);
        if(t==null){
            if(tasks.size()>=capacity){overflow++;epoch++;return;}
            t=new Task(chunk,0);t.offerResolved=true;t.accepted=true;tasks.put(task,t);inline++;
        }
        if(t.running||t.returned){errors++;epoch++;return;}
        t.running=true;starts++;epoch++;
    }
    synchronized void returned(Object task,boolean success,boolean skipped) {
        Task t=tasks.get(task);
        if(t==null){if(overflow==0)errors++;epoch++;return;}
        if(!t.running){errors++;epoch++;return;}
        t.running=false;t.returned=true;returns++;if(!success)errors++;
        if(skipped&&!t.cancelled){
            if(skippedChunks.size()>=capacity&&!skippedChunks.containsKey(t.chunk))overflow++;
            else skippedChunks.put(t.chunk,Boolean.TRUE);
        }else if(success&&!t.cancelled)skippedChunks.remove(t.chunk);
        epoch++;removeIfDone(task,t);
    }
    synchronized void cancel(Object task) {
        Task t=tasks.get(task);if(t==null)return;
        if(!t.cancelled){t.cancelled=true;cancelled++;epoch++;}
        removeIfDone(task,t);
    }
    private void removeIfDone(Object key,Task t) {
        if(t.offerResolved&&!t.running&&(!t.accepted||t.cancelled||t.returned))tasks.remove(key);
    }
    synchronized void dirty(){epoch++;}
    synchronized boolean skipped(Object chunk){return skippedChunks.containsKey(chunk);}
    synchronized Snapshot snapshot(){
        int active=0;for(Task t:tasks.values())if(t.running)active++;
        return new Snapshot(epoch,tasks.size()-active,active,overflow,errors);
    }
    synchronized Map<String,Object> summary(){
        Map<String,Object> m=new LinkedHashMap<>();Snapshot s=snapshot();
        m.put("epoch",epoch);m.put("offers",offers);m.put("accepted",accepted);m.put("rejected",rejected);
        m.put("inlineTasks",inline);m.put("workerStarts",starts);m.put("workerReturns",returns);m.put("cancelled",cancelled);
        m.put("queuedOrOfferUnresolved",s.queued);m.put("activeWorkers",s.active);m.put("unresolvedSkippedChunks",skippedChunks.size());
        m.put("overflow",overflow);m.put("lifecycleErrors",errors);return m;
    }
    static final class Snapshot {
        final long epoch,overflow,errors;final int queued,active;
        Snapshot(long epoch,int queued,int active,long overflow,long errors){this.epoch=epoch;this.queued=queued;this.active=active;this.overflow=overflow;this.errors=errors;}
        boolean idle(){return queued==0&&active==0&&overflow==0&&errors==0;}
    }
    private static final class Task {
        final Object chunk;final long token;boolean offerResolved,accepted,running,returned,cancelled;
        Task(Object chunk,long token){this.chunk=chunk;this.token=token;}
    }
}
