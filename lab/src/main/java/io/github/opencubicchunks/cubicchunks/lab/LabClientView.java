package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProvider;
import net.minecraft.client.Minecraft;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual residency for the same immutable target set as the server; no synthetic render-ready claim. */
final class LabClientView {
    private final LabViewGeometry geometry;
    private final LabFullViewRenderReady renderReady;
    private int ticks, loaded, queueQuiescentFrames, maximumQueueQuiescentFrames;
    private long renderObservations;
    private long observedAt;
    LabClientView(JsonObject data,boolean requireRenderReady) { geometry=LabViewGeometry.read(data);renderReady=requireRenderReady?new LabFullViewRenderReady(geometry):null; }
    void tick(Minecraft mc, String id, String scenario) {
        if(++ticks%20!=0)return;
        if(Math.abs(mc.player.posX-geometry.x)>.3||Math.abs(mc.player.posY-geometry.y)>.3||Math.abs(mc.player.posZ-geometry.z)>.3)return;
        ICubeProvider provider=((ICubicWorld)mc.world).getCubeCache(); loaded=0;
        for(int x=geometry.minX;x<=geometry.maxX;x++)for(int y=geometry.minY;y<=geometry.maxY;y++)for(int z=geometry.minZ;z<=geometry.maxZ;z++)
            if(provider.getLoadedCube(x,y,z)!=null)loaded++;
        observedAt=System.nanoTime();
        if(loaded==LabViewGeometry.EXPECTED&&renderReady!=null)renderReady.dataReady();
        JsonObject report=CubicLab.control("preparation-view",id,scenario);report.addProperty("loaded",loaded);
        report.addProperty("renderQueueQuiescentFrames",queueQuiescentFrames);
        if(renderReady!=null){report.addProperty("rendererReady",renderReady.ready());if(renderReady.failure()!=null)report.addProperty("rendererReadyFailure",renderReady.failure());}
        Network.CHANNEL.sendToServer(new Network.ServerMessage(report));
    }
    void render(Minecraft mc) {
        renderObservations++;
        if(loaded==LabViewGeometry.EXPECTED && mc.currentScreen==null && mc.renderGlobal.hasNoChunkUpdates())queueQuiescentFrames++;
        else queueQuiescentFrames=0;
        maximumQueueQuiescentFrames=Math.max(maximumQueueQuiescentFrames,queueQuiescentFrames);
        if(renderReady!=null)renderReady.end(mc,loaded==LabViewGeometry.EXPECTED);
    }
    void renderStart(Minecraft mc){if(renderReady!=null)renderReady.begin(mc);}
    void dataChanged(){if(renderReady!=null){loaded=0;renderReady.dataChanged();}}
    void close(){if(renderReady!=null)renderReady.close();}
    Map<String,Object> summary() {
        Map<String,Object> data=new LinkedHashMap<>(); data.put("geometry",geometry.json());data.put("resident",loaded);
        data.put("lastObservationMonotonicNanos",observedAt);data.put("meshUploadDrawCompletionMeasured",false);
        data.put("renderQueueObservations",renderObservations);data.put("renderQueueQuiescentFrames",queueQuiescentFrames);
        data.put("maximumRenderQueueQuiescentFrames",maximumQueueQuiescentFrames);
        data.put("renderReadyRequired",renderReady!=null);if(renderReady!=null)data.put("rendererReady",renderReady.summary());
        data.put("rendererFrameCommandCompletionMeasured",renderReady!=null);
        data.put("scope",renderReady==null?"Client cube residency in the required distance-12 target set, every 20 client ticks. Vanilla render queues are observed on the render owner after frames; currently compiling worker tasks may be between queues. Queue quiescence is neither complete meshing nor a GPU fence proof.":"Client cube residency remains the required distance-12 target set. The optional rendererReady object additionally tracks fixed-camera/frustum demand, workers, owner queues and deferred frame GL commands. No claim that every resident cube has a mesh or that a frame was presented.");return data;
    }
}
