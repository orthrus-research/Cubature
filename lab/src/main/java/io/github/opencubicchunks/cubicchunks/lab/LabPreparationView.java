package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.server.PlayerCubeMap;
import io.github.opencubicchunks.cubicchunks.core.server.CubeWatcher;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.GameType;
import java.util.*;

/** Measurement only: wait for one fixed, complete 25-cubed viewer footprint; never force its generation. */
final class LabPreparationView {
    private final Map<UUID,Integer> clients = new HashMap<>();
    private final Map<UUID,Boolean> rendererReady = new HashMap<>();
    private final Map<UUID,String> rendererFailures = new HashMap<>();
    private final boolean requireRenderReady=Boolean.getBoolean("cubiclab.fullViewRenderReady");
    private final List<Map<String,Object>> samples = new ArrayList<>();
    private final LabViewGeometry geometry;
    private final CubePos[] positions = new CubePos[LabViewGeometry.EXPECTED];
    private final long start = System.nanoTime();
    /** The lab tick of the preparation teleport. */
    private final int teleportTick;
    private final Set<UUID> arrived = new HashSet<>();
    private long arrivedNanos, readyNanos, deliveredNanos,renderReadyNanos,previousDataIncompleteNanos;
    private long previousIncompleteNanos;
    private int serverReady, serverSent;
    LabPreparationView(LabJob job) {
        geometry=job.fullView;
        teleportTick=job.elapsedTicks;
        int index=0;
        for(int x=geometry.minX;x<=geometry.maxX;x++)for(int y=geometry.minY;y<=geometry.maxY;y++)for(int z=geometry.minZ;z<=geometry.maxZ;z++)
            positions[index++]=new CubePos(x,y,z);
        for (EntityPlayerMP player : job.server.getPlayerList().getPlayers()) if(job.peers.contains(player.getUniqueID())) {
            player.setGameType(GameType.SPECTATOR);
            player.connection.setPlayerLocation(geometry.x,geometry.y,geometry.z,geometry.yaw,geometry.pitch);
            clients.put(player.getUniqueID(),0);
            rendererReady.put(player.getUniqueID(),false);
        }
        if(clients.isEmpty())throw new IllegalStateException("Full view requires a real client");
    }
    void client(EntityPlayerMP player,int loaded,com.google.gson.JsonObject report) {
        if(loaded<0||loaded>LabViewGeometry.EXPECTED)throw new IllegalArgumentException("Invalid full-view residency count");
        if(clients.containsKey(player.getUniqueID())) {
            clients.put(player.getUniqueID(),loaded);
            if(requireRenderReady){
                rendererReady.put(player.getUniqueID(),report.has("rendererReady")&&report.get("rendererReady").getAsBoolean());
                if(report.has("rendererReadyFailure"))rendererFailures.put(player.getUniqueID(),report.get("rendererReadyFailure").getAsString());
            }
        }
    }
    boolean tick(LabJob job) {
        if(!rendererFailures.isEmpty())throw new IllegalStateException("Full-view renderer qualification failed: "+rendererFailures);
        // Cubature holds a teleport into an unprepared column until its workers prepare it: wait for
        // each participant's first arrival (within the full-view timeout), then require it to stay.
        boolean waiting=false;
        for(UUID id:clients.keySet()) {
            EntityPlayerMP p=job.server.getPlayerList().getPlayerByUUID(id);
            if(p==null)throw new IllegalStateException("Full-view participant disconnected");
            boolean there=Math.abs(p.posX-geometry.x)<=.3 && Math.abs(p.posY-geometry.y)<=.3 && Math.abs(p.posZ-geometry.z)<=.3
                    &&Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(p.rotationYaw-geometry.yaw))<=2&&Math.abs(p.rotationPitch-geometry.pitch)<=2;
            if(there)arrived.add(id);
            else if(arrived.contains(id))throw new IllegalStateException("Full-view participant moved");
            else waiting=true;
        }
        if(waiting){
            // Cubature completes a held teleport within TIMEOUT_TICKS: a participant still away 200 ticks
            // later lost its teleport, so fail now, naming that, instead of at the full-view timeout.
            long waited=job.elapsedTicks-teleportTick, limit=io.github.opencubicchunks.cubicchunks.core.server.DeferredArrivals.TIMEOUT_TICKS+200;
            if(waited>limit)throw new IllegalStateException("Full-view participant did not arrive within "+limit+" ticks of its teleport; Cubature completes a held teleport within "+(limit-200)+" ticks, so the teleport was lost");
            if(System.nanoTime()-start>job.fullViewTimeoutSeconds*1000000000L)throw new IllegalStateException("Full-view participant did not arrive within " + job.fullViewTimeoutSeconds + " seconds");
            return false;
        }
        if(arrivedNanos==0)arrivedNanos=System.nanoTime()-start;
        if(job.elapsedTicks%20!=0)return false;
        long probeStarted=System.nanoTime();
        serverReady=0;serverSent=0;
        PlayerCubeMap map=(PlayerCubeMap)job.world.getPlayerChunkMap();
        for(CubePos pos:positions) {
            Cube cube=job.provider.getLoadedCube(pos);
            if(cube!=null && cube.isFullyPopulated() && cube.isInitialLightingDone() && cube.isSurfaceTracked())serverReady++;
            CubeWatcher watcher=map.getCubeWatcher(pos);
            if(watcher!=null && watcher.isSentToPlayers())serverSent++;
        }
        long age=System.nanoTime()-start;
        job.stages.record("fullViewCoverageProbe",probeStarted);
        Map<String,Object> row=new LinkedHashMap<>();row.put("nanos",age);row.put("ready",serverReady);row.put("sent",serverSent);
        row.put("clientMinimum",Collections.min(clients.values()));row.put("backlog",15625-serverReady);samples.add(row);
        if(requireRenderReady){int count=0;for(boolean value:rendererReady.values())if(value)count++;row.put("rendererReadyClients",count);}
        if(serverReady==15625 && readyNanos==0)readyNanos=age;
        if(serverReady==15625 && serverSent==15625 && Collections.min(clients.values())==15625) {
            if(deliveredNanos==0)deliveredNanos=age;
            if(!requireRenderReady)return true;
            if(!rendererReady.containsValue(false)){renderReadyNanos=age;return true;}
        }else previousDataIncompleteNanos=age;
        previousIncompleteNanos=age;
        if(age>job.fullViewTimeoutSeconds*1000000000L)throw new IllegalStateException("Full view not delivered within " + job.fullViewTimeoutSeconds + " seconds; retain incomplete work");
        return false;
    }
    Map<String,Object> summary() {
        Map<String,Object> data=new LinkedHashMap<>();data.put("expected",15625);data.put("serverReady",serverReady);data.put("serverSent",serverSent);
        data.put("geometry",geometry.json());data.put("previousIncompleteObservationNanos",previousIncompleteNanos);
        data.put("clients",clients);data.put("arrivedNanos",arrivedNanos);data.put("readyNanos",readyNanos);data.put("deliveredNanos",deliveredNanos);data.put("samples",samples);
        data.put("previousDataIncompleteObservationNanos",previousDataIncompleteNanos);data.put("renderReadyRequired",requireRenderReady);
        data.put("rendererReadyClients",rendererReady);data.put("rendererFailures",rendererFailures);data.put("serverObservedRenderReadyNanos",renderReadyNanos);
        data.put("scope","Ordinary streaming; one fixed full view, including cubes outside Riven height. Observation cadence 20 server/client ticks. Delivery is an observed upper bound; previous incomplete observation is retained. No forced requests or save flush in timed view. Client residency is not mesh/upload/draw completion.");
        if(requireRenderReady)data.put("renderReadyScope","Capture continues after data delivery until every client reports fixed-camera/frustum demand quiescent across consecutive frames and a deferred GL timestamp available. Includes all observed waits in end-to-end time. Does not require meshes for culled cubes or measure SDL presentation.");
        return data;
    }
}
