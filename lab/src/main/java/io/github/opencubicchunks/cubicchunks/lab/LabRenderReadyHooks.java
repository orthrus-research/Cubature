package io.github.opencubicchunks.cubicchunks.lab;

import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import net.minecraft.client.renderer.chunk.RenderChunk;
import io.github.opencubicchunks.cubicchunks.lab.mixin.RenderReadyWorkerAccess;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in startup lifetime, independent of individual capture windows; no GL calls on workers. */
public final class LabRenderReadyHooks {
    private static final boolean ENABLED=Boolean.getBoolean("cubiclab.fullViewRenderReady") || LabDeliveryTrace.ENABLED;
    private static volatile State current;
    private static long worlds;
    private LabRenderReadyHooks(){}
    public static synchronized void world(Object world){if(ENABLED)current=new State(world,++worlds);}
    private static State state(Object world){State s=current;return ENABLED&&s!=null&&s.world==world?s:null;}
    public static long beforeOffer(ChunkCompileTaskGenerator task){
        trace("mesh-offer",task.getRenderChunk(),System.identityHashCode(task));
        State s=state(task.getRenderChunk().getWorld());return s==null?0:s.ledger.offer(task,task.getRenderChunk());
    }
    public static void afterOffer(ChunkCompileTaskGenerator task,long token,boolean accepted){
        State s=state(task.getRenderChunk().getWorld());if(s!=null)s.ledger.offered(task,token,accepted);
    }
    public static void process(ChunkRenderWorker worker,ChunkCompileTaskGenerator task)throws InterruptedException{
        trace("mesh-start",task.getRenderChunk(),System.identityHashCode(task));
        State s=state(task.getRenderChunk().getWorld());if(s!=null)s.ledger.start(task,task.getRenderChunk());
        boolean success=false;
        try{((RenderReadyWorkerAccess)worker).cubiclab$processTask(task);success=true;}
        finally{trace(success?"mesh-return":"mesh-failed",task.getRenderChunk(),System.identityHashCode(task));if(s!=null)s.ledger.returned(task,success,task.getStatus()==ChunkCompileTaskGenerator.Status.PENDING&&!task.isFinished());}
    }
    public static void cancelled(ChunkCompileTaskGenerator task){State s=state(task.getRenderChunk().getWorld());if(s!=null)s.ledger.cancel(task);}
    public static void dirty(RenderChunk chunk){State s=state(chunk.getWorld());if(s!=null)s.ledger.dirty();}
    public static void dataChanged(Object world){State s=state(world);if(s!=null)s.ledger.dirty();}
    public static void trace(String op,RenderChunk chunk,long token){
        if(!LabDeliveryTrace.ENABLED)return;
        net.minecraft.util.math.BlockPos p=chunk.getPosition();
        LabDeliveryTrace.event(op,p.getX()>>4,p.getY()>>4,p.getZ()>>4,token,System.identityHashCode(chunk));
    }
    public static boolean skipped(RenderChunk chunk){State s=state(chunk.getWorld());return s!=null&&s.ledger.skipped(chunk);}
    static long worldId(Object world){State s=state(world);return s==null?0:s.id;}
    static RenderTaskLedger.Snapshot snapshot(Object world){State s=state(world);return s==null?null:s.ledger.snapshot();}
    static Map<String,Object> summary(Object world){
        State s=state(world);if(s!=null){Map<String,Object> m=s.ledger.summary();m.put("worldGeneration",s.id);return m;}
        Map<String,Object> m=new LinkedHashMap<>();m.put("unavailable",true);return m;
    }
    private static final class State {
        final Object world;final long id;final RenderTaskLedger ledger=new RenderTaskLedger(32768);
        State(Object world,long id){this.world=world;this.id=id;}
    }
}
