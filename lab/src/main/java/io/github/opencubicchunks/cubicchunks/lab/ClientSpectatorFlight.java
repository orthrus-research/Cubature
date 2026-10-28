package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovementInput;
import java.util.*;

/** Scripted spectator velocity through vanilla movement packets; never overrides position. */
final class ClientSpectatorFlight {
    final LabFlightPlan plan;
    final String id, scenario;
    final boolean moving;
    final List<Map<String,Object>> observations = new ArrayList<>();
    long started, ended, lastObservation;
    double startX, startY, startZ;
    int movementTicks;
    boolean done, passed;
    String failure;
    ClientSpectatorFlight(JsonObject control, LabBenchmarkWorkload workload, boolean moving) {
        plan = new LabFlightPlan(workload.data); id = control.get("runId").getAsString();
        scenario = control.get("scenario").getAsString(); this.moving = moving;
    }
    void input(Minecraft mc, MovementInput input) {
        input.moveForward=0;input.moveStrafe=0;input.jump=false;input.sneak=false;
        mc.player.setSprinting(false);
        if (!moving || done) { mc.player.motionX=mc.player.motionY=mc.player.motionZ=0; return; }
        long now=System.nanoTime();
        if (started==0) {
            started=now;startX=mc.player.posX;startY=mc.player.posY;startZ=mc.player.posZ;
            JsonObject report=CubicLab.control("flight-started",id,scenario);
            Network.CHANNEL.sendToServer(new Network.ServerMessage(report));
        }
        if (!mc.player.isSpectator()) { finish(mc,false,"Client left spectator mode",now);return; }
        if (plan.finished(started,now)) {
            finish(mc,plan.validOutcome(now-started,mc.player.posX-startX)
                && Math.abs(mc.player.posY-startY)<.5 && Math.abs(mc.player.posZ-startZ)<.5,null,now);return;
        }
        movementTicks++;
        mc.player.rotationYaw=-90;mc.player.rotationPitch=15;
        mc.player.motionX=plan.velocityPerClientTick();mc.player.motionY=0;mc.player.motionZ=0;
    }
    void tick(Minecraft mc, long captureStarted, long loads, long unloads) {
        long now=System.nanoTime();
        if(now-lastObservation<1000000000L)return;lastObservation=now;
        ICubeProvider provider=((ICubicWorld)mc.world).getCubeCache();
        int cx=(int)Math.floor(mc.player.posX/16),cy=(int)Math.floor(mc.player.posY/16),cz=(int)Math.floor(mc.player.posZ/16),resident=0,near=0;
        long begin=System.nanoTime();
        if(LabDeliveryTrace.ENABLED)LabDeliveryTrace.event("client-view-probe",cx,cy,cz,observations.size(),0);
        for(int x=cx-12;x<=cx+12;x++)for(int y=cy-12;y<=cy+12;y++)for(int z=cz-12;z<=cz+12;z++)
            if(provider.getLoadedCube(x,y,z)!=null){resident++;if(Math.abs(x-cx)<=2&&Math.abs(y-cy)<=2&&Math.abs(z-cz)<=2)near++;}
            else if(LabDeliveryTrace.ENABLED)LabDeliveryTrace.event("client-missing",x,y,z,observations.size(),0);
        Map<String,Object> row=new LinkedHashMap<>();row.put("nanos",now-captureStarted);
        row.put("pose",new double[]{mc.player.posX,mc.player.posY,mc.player.posZ,mc.player.rotationYaw,mc.player.rotationPitch});
        row.put("resident",resident);row.put("nearResident",near);
        int total=0;
        for(net.minecraft.world.chunk.Chunk column:((io.github.opencubicchunks.cubicchunks.core.client.CubeProviderClient)provider).getLoadedChunks())
            total+=((io.github.opencubicchunks.cubicchunks.api.world.IColumn)column).getLoadedCubes().size();
        row.put("loadedCubes",total);row.put("cubeLoadEvents",loads);row.put("cubeUnloadEvents",unloads);
        row.put("packetCount",LabHooks.clientPackets.count.get());row.put("receivedCubeTransmissions",LabHooks.clientPackets.cubes.get());
        row.put("usedHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        row.put("probeNanos",System.nanoTime()-begin);
        if(observations.size()>=400)throw new IllegalStateException("Flight observation capacity");observations.add(row);
        JsonObject report=CubicLab.control("flight-view",id,scenario);report.addProperty("resident",resident);
        Network.CHANNEL.sendToServer(new Network.ServerMessage(report));
    }
    private void finish(Minecraft mc,boolean passed,String failure,long now) {
        done=true;ended=now;this.passed=passed;this.failure=passed?null:(failure==null?"Flight speed/duration/altitude outside tolerance":failure);
        mc.player.motionX=mc.player.motionY=mc.player.motionZ=0;
        JsonObject report=new Gson().toJsonTree(summary(mc)).getAsJsonObject();report.remove("observations");
        report.addProperty("op","flight-finished");report.addProperty("runId",id);report.addProperty("scenario",scenario);
        Network.CHANNEL.sendToServer(new Network.ServerMessage(report));
    }
    Map<String,Object> summary(Minecraft mc) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("moving",moving);out.put("passed",!moving||passed);out.put("failure",failure);
        out.put("movementTicks",movementTicks);out.put("movementElapsedNanos",started==0?0:(ended==0?System.nanoTime():ended)-started);
        out.put("distanceX",started==0?0:mc.player.posX-startX);out.put("startX",startX);out.put("startY",startY);out.put("startZ",startZ);
        out.put("finalPose",new double[]{mc.player.posX,mc.player.posY,mc.player.posZ,mc.player.rotationYaw,mc.player.rotationPitch});
        out.put("observations",observations);out.put("inRoutePositionOverrides",0);
        out.put("scope","Client scripted X velocity, native spectator physics and ordinary player movement packets; 60-second client wall-clock endpoint. Residency is sampled once per second, not mesh or presentation readiness.");return out;
    }
}
