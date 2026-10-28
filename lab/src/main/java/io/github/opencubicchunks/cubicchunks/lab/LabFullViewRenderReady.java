package io.github.opencubicchunks.cubicchunks.lab;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import java.util.LinkedHashMap;
import java.util.Map;

/** Full data set plus actual fixed-camera renderer demand; never a claim that every cube needs a mesh. */
public final class LabFullViewRenderReady {
    private static LabFullViewRenderReady active;
    private final LabViewGeometry geometry;
    private final long started=System.nanoTime();
    private final String originUtc=java.time.Instant.now().toString();
    private DeferredFrameReady marker;
    private String failure;
    private Object world;
    private long worldId,beginEpoch,lastLedgerEpoch=Long.MIN_VALUE,lastSignature=Long.MIN_VALUE,generation;
    private long dataReadyNanos,queuesReadyNanos,frameCommandsReadyNanos,invalidations,validatedLedgerEpoch;
    private int[] demand=new int[6];
    private boolean ready,pendingFrame,pendingIdle;
    LabFullViewRenderReady(LabViewGeometry geometry){
        this.geometry=geometry;
        active=this;
        if(!Boolean.getBoolean("cubiclab.fullViewRenderReady")){failure="Client full-view render-ready property does not match server";return;}
        try{
            if(!LabGlCapabilities.openGl33()){failure="OpenGL33 timestamps unavailable";return;}
            marker=new DeferredFrameReady(new Queries());
        }catch(RuntimeException|LinkageError ex){failure="GPU marker unavailable: "+ex.getClass().getSimpleName();}
    }
    void dataReady(){if(dataReadyNanos==0)dataReadyNanos=System.nanoTime()-started;}
    void dataChanged(){ready=false;generation++;invalidations++;}
    void begin(Minecraft mc){
        pendingFrame=false;
        if(marker==null||failure!=null)return;
        long id=LabRenderReadyHooks.worldId(mc.world);
        if(world==null){world=mc.world;worldId=id;}
        if(world!=mc.world||id==0||worldId!=id){failure="Renderer world changed or startup tracking unavailable";return;}
        RenderTaskLedger.Snapshot s=LabRenderReadyHooks.snapshot(mc.world);
        beginEpoch=s==null?Long.MIN_VALUE:s.epoch;
        if(beginEpoch!=lastLedgerEpoch){generation++;lastLedgerEpoch=beginEpoch;}
        marker.begin(generation);
    }
    void end(Minecraft mc,boolean allResident){
        if(marker==null||failure!=null)return;
        try{
            if(!(mc.renderGlobal instanceof LabRenderReadyAccess)){failure="Baseline renderer readiness hooks unavailable";return;}
            RenderTaskLedger.Snapshot s=LabRenderReadyHooks.snapshot(mc.world);
            if(s==null||world!=mc.world||worldId!=LabRenderReadyHooks.worldId(mc.world)){failure="Renderer world changed";return;}
            if(s.overflow!=0||s.errors!=0){failure="Renderer lifecycle overflow or error";return;}
            demand=((LabRenderReadyAccess)mc.renderGlobal).cubiclab$renderDemand();
            RenderTaskLedger.Snapshot after=LabRenderReadyHooks.snapshot(mc.world);
            if(after==null){failure="Renderer world changed";return;}
            boolean snapshotStable=s.epoch==after.epoch;s=after;
            if(s.epoch!=lastLedgerEpoch||demand[5]!=lastSignature){generation++;lastLedgerEpoch=s.epoch;lastSignature=demand[5];}
            boolean camera=cameraMatches(mc);
            boolean idle=allResident&&camera&&snapshotStable&&s.idle()&&s.epoch==beginEpoch&&mc.renderGlobal.hasNoChunkUpdates()
                    &&demand[0]>0&&demand[1]==0&&demand[2]==0&&demand[3]==0&&demand[4]==0;
            if(idle&&queuesReadyNanos==0)queuesReadyNanos=System.nanoTime()-started;
            pendingFrame=true;pendingIdle=idle;validatedLedgerEpoch=s.epoch;
        }catch(RuntimeException|LinkageError ex){failure="Renderer readiness failed: "+ex.getClass().getSimpleName();ready=false;}
    }
    /** Minecraft.updateDisplay HEAD follows the final framebuffer blit and precedes swap/presentation. */
    public static void afterFrameCommands(){LabFullViewRenderReady value=active;if(value!=null)value.pollAfterFrameCommands();}
    private void pollAfterFrameCommands(){
        if(!pendingFrame||marker==null||failure!=null)return;pendingFrame=false;
        try{
            RenderTaskLedger.Snapshot s=LabRenderReadyHooks.snapshot(world);
            if(s==null||worldId!=LabRenderReadyHooks.worldId(world)){failure="Renderer world changed";ready=false;return;}
            if(s.epoch!=validatedLedgerEpoch){generation++;pendingIdle=false;}
            boolean previous=ready;ready=marker.end(generation,pendingIdle&&s.idle());validatedLedgerEpoch=s.epoch;
            if(previous&&!ready)invalidations++;
            if(ready&&frameCommandsReadyNanos==0)frameCommandsReadyNanos=System.nanoTime()-started;
        }catch(RuntimeException|LinkageError ex){failure="GPU frame marker failed: "+ex.getClass().getSimpleName();ready=false;}
    }
    boolean ready(){
        if(ready){RenderTaskLedger.Snapshot s=LabRenderReadyHooks.snapshot(world);if(s==null||!s.idle()||s.epoch!=validatedLedgerEpoch||!cameraMatches(Minecraft.getMinecraft())){ready=false;invalidations++;}}
        return ready&&failure==null;
    }
    private boolean cameraMatches(Minecraft mc){
        return mc.world==world&&mc.player!=null&&mc.getRenderViewEntity()==mc.player&&mc.currentScreen==null
                &&mc.gameSettings.renderDistanceChunks==LabViewGeometry.DISTANCE
                &&geometry.matchesPose(mc.player.posX,mc.player.posY,mc.player.posZ,mc.player.rotationYaw,mc.player.rotationPitch);
    }
    String failure(){return failure;}
    Map<String,Object> summary(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("required",true);m.put("ready",ready());m.put("failure",failure);
        m.put("endpointOriginMonotonicNanos",started);m.put("endpointOriginUtc",originUtc);
        m.put("dataReadyNanos",dataReadyNanos);m.put("firstMeshUploadQueuesReadyNanos",queuesReadyNanos);
        m.put("firstFrameCommandsReadyNanos",frameCommandsReadyNanos);m.put("invalidations",invalidations);
        m.put("visibleDemand",demand[0]);m.put("dirtyDemand",demand[1]);m.put("uncompiledDemand",demand[2]);
        m.put("skippedDemand",demand[3]);m.put("pendingLightUpdates",demand[4]);m.put("taskLedger",LabRenderReadyHooks.summary(world));
        if(Minecraft.getMinecraft().renderGlobal instanceof LabRenderReadyAccess){
            LabMeshDemandSnapshot locations=new LabMeshDemandSnapshot(geometry);
            ((LabRenderReadyAccess)Minecraft.getMinecraft().renderGlobal).cubiclab$visitOutstanding(locations);
            m.put("outstandingLocations",locations.summary());
        }
        if(marker!=null){m.put("consecutiveStableFrames",marker.stableFrames());m.put("markersIssued",marker.issued());m.put("markersResolved",marker.resolved());m.put("markersInvalidated",marker.invalidated());m.put("gpuTimestamp",marker.gpuTimestamp());}
        m.put("scope","Required 15625 cube residency plus fixed camera/frustum renderer demand, pending lighting/dirty/update/upload queues and all tracked workers quiescent. After an unchanged generation and consecutive frames, a GL timestamp is inserted at Minecraft.updateDisplay HEAD after the final framebuffer blit and polled available on later frames. This is neither meshes for every resident cube nor SDL swap/presentation completion. Unsupported tracking/GL, lifecycle error/overflow or timeout fails this optional lane.");return m;
    }
    void close(){if(marker!=null)marker.close();if(active==this)active=null;}
    private static final class Queries implements DeferredFrameReady.Backend {
        public int create(){return GL15.glGenQueries();}public void timestamp(int q){GL33.glQueryCounter(q,GL33.GL_TIMESTAMP);}
        public boolean available(int q){return GL15.glGetQueryObjecti(q,GL15.GL_QUERY_RESULT_AVAILABLE)!=0;}
        public long result(int q){return GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT);}
        public void delete(int q){GL15.glDeleteQueries(q);}
    }
}
