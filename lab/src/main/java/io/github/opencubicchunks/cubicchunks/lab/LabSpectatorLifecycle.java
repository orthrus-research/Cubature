package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;
import io.github.opencubicchunks.cubicchunks.core.server.ChunkGc;
import io.github.opencubicchunks.cubicchunks.core.server.PlayerCubeMap;
import io.github.opencubicchunks.cubicchunks.core.server.CubeWatcher;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.GameType;
import java.util.*;

/** Observation and explicit post-route save/eviction; no terrain or ticket mutations. */
final class LabSpectatorLifecycle {
    final LabFlightPlan plan;
    final List<Map<String,Object>> observations=new ArrayList<>();
    JsonObject clientResult;
    boolean controlSent, purgeDone;
    int clientResident;
    long flightStarted, lastObservation, finishedAt, saveNanos, purgeNanos, flushNanos;
    int beforeSave,beforePurge,afterPurge, pendingAfterFlush;
    double[] serverFinalPose;
    LabSpectatorLifecycle(LabJob job){plan=new LabFlightPlan(job.benchmark.data);}
    private EntityPlayerMP player(LabJob job) {
        if(job.peers.size()!=1)throw new IllegalStateException("Lifecycle requires exactly one real client");
        EntityPlayerMP p=job.server.getPlayerList().getPlayerByUUID(job.peers.iterator().next());
        if(p==null||!p.isSpectator()||p.interactionManager.getGameType()!=GameType.SPECTATOR)throw new IllegalStateException("Spectator disconnected or changed mode");return p;
    }
    boolean tick(LabJob job)throws java.io.IOException {
        EntityPlayerMP p=player(job);long now=System.nanoTime();
        if(!controlSent){JsonObject control=CubicLab.control("flight-begin",job.id,job.scenario);
            control.addProperty("moving",job.scenario.equals("spectator-flight"));Network.CHANNEL.sendTo(new Network.ClientMessage(control),p);controlSent=true;}
        if(now-lastObservation>=1000000000L){lastObservation=now;observe(job,p,now);}
        if(job.scenario.equals("spectator-flight")) {
            if(clientResult==null)return false;
            if(!clientResult.get("passed").getAsBoolean())throw new IllegalStateException("Client flight failed: "+clientResult);
            serverFinalPose=new double[]{p.posX,p.posY,p.posZ,p.rotationYaw,p.rotationPitch};
            return true;
        }
        if(job.scenario.equals("lifecycle-settle")) {
            return now-job.started>=plan.settleSeconds*1000000000L && clientResident==15625
                && !observations.isEmpty() && ((Number)observations.get(observations.size()-1).get("ready")).intValue()==15625;
        }
        if(!purgeDone) {
            beforeSave=job.provider.getLoadedCubeCount();long begin=System.nanoTime();
            job.provider.saveChunks(true);saveNanos=System.nanoTime()-begin;job.submissionNanos+=saveNanos;
            begin=System.nanoTime();job.provider.getCubeIO().flush();flushNanos+=System.nanoTime()-begin;
            beforePurge=job.provider.getLoadedCubeCount();begin=System.nanoTime();
            new ChunkGc(job.provider).chunkGc();purgeNanos=System.nanoTime()-begin;afterPurge=job.provider.getLoadedCubeCount();
            begin=System.nanoTime();job.provider.getCubeIO().flush();flushNanos+=System.nanoTime()-begin;
            job.flushNanos+=flushNanos;pendingAfterFlush=job.provider.getCubeIO().getPendingCubeCount()+job.provider.getCubeIO().getPendingColumnCount();
            purgeDone=true;finishedAt=System.nanoTime();
            if(pendingAfterFlush!=0)throw new IllegalStateException("Save/purge flush left pending writes");
        }
        return now-finishedAt>=2000000000L;
    }
    private void observe(LabJob job,EntityPlayerMP p,long now) {
        int cx=(int)Math.floor(p.posX/16),cy=(int)Math.floor(p.posY/16),cz=(int)Math.floor(p.posZ/16),resident=0,ready=0,sent=0,near=0,dirty=0;
        long begin=System.nanoTime();PlayerCubeMap map=(PlayerCubeMap)job.world.getPlayerChunkMap();
        if(LabDeliveryTrace.ENABLED)LabDeliveryTrace.event("server-view-probe",cx,cy,cz,observations.size(),0);
        for(int x=cx-12;x<=cx+12;x++)for(int y=cy-12;y<=cy+12;y++)for(int z=cz-12;z<=cz+12;z++){
            CubePos pos=new CubePos(x,y,z);Cube cube=job.provider.getLoadedCube(pos);
            if(cube!=null){resident++;if(cube.isFullyPopulated()&&cube.isInitialLightingDone()&&cube.isSurfaceTracked()){ready++;if(Math.abs(x-cx)<=2&&Math.abs(y-cy)<=2&&Math.abs(z-cz)<=2)near++;}}
            else if(LabDeliveryTrace.ENABLED)LabDeliveryTrace.event("server-missing",x,y,z,observations.size(),0);
            if(cube!=null&&cube.needsSaving())dirty++;
            CubeWatcher watcher=map.getCubeWatcher(pos);if(watcher!=null&&watcher.isSentToPlayers())sent++;
        }
        Map<String,Object> row=new LinkedHashMap<>();row.put("nanos",now-job.started);row.put("pose",new double[]{p.posX,p.posY,p.posZ,p.rotationYaw,p.rotationPitch});
        row.put("resident",resident);row.put("ready",ready);row.put("nearReady",near);row.put("sent",sent);row.put("clientResidentLatest",clientResident);
        row.put("loadedCubes",job.provider.getLoadedCubeCount());row.put("pendingCubeWrites",job.provider.getCubeIO().getPendingCubeCount());row.put("pendingColumnWrites",job.provider.getCubeIO().getPendingColumnCount());
        row.put("dirtyResidentViewCubes",dirty);row.put("pendingAutosaveSnapshots",job.provider.getPendingAutosaveCount());
        row.put("pendingAutosaveDiscovery",job.provider.getPendingAutosaveDiscoveryCount());row.put("lastAutosaveProcessed",job.provider.getLastAutosaveProcessedCount());
        row.put("cubeLoadEvents",job.cubeLoads);row.put("cubeUnloadEvents",job.cubeUnloads);row.put("cubeDataSaveEvents",job.dataSaves);
        row.put("packetCount",job.packets.count.get());row.put("sentCubeTransmissions",job.packets.cubes.get());
        row.put("usedHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        row.put("probeNanos",System.nanoTime()-begin);
        if(observations.size()>=400)throw new IllegalStateException("Lifecycle observation capacity");observations.add(row);
    }
    Map<String,Object> summary() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("observations",observations);out.put("clientFlight",clientResult);
        out.put("serverFlightStartOffsetNanos",flightStarted);out.put("saveSubmissionNanos",saveNanos);out.put("explicitPurgeNanos",purgeNanos);out.put("blockingFlushNanos",flushNanos);
        out.put("loadedBeforeSave",beforeSave);out.put("loadedBeforePurge",beforePurge);out.put("loadedAfterPurge",afterPurge);out.put("pendingAfterFlush",pendingAfterFlush);
        out.put("serverFinalPose",serverFinalPose);
        out.put("scope","Per-second authoritative moving-view observations. Natural unload/save events remain in flight costs. Explicit save, ticket-respecting purge and flush occur only after flight and settling. Purge evicts memory; region files are retained.");return out;
    }
}
