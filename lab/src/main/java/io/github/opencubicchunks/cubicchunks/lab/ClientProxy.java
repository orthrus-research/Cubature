package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.opencubicchunks.cubicchunks.api.world.CubeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;
import java.time.Instant;
import java.util.*;

/** Only loaded on a real client. Reports frame pacing separately from render-event work. */
public final class ClientProxy extends CommonProxy {
    private Capture active;
    private JsonObject pending;
    private LabBenchmarkWorkload pendingBenchmark;
    private LabOverlayProbe overlays;
    private long pendingAt;
    private int pendingOverlayTicks;
    private long lodTicks;
    private String last = "Ready: /cubiclab run vertical";
    private String lastScreen;
    public void initialize() { overlays=new LabOverlayProbe();MinecraftForge.EVENT_BUS.register(this); }
    public void clientMessage(String message) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            JsonObject data = new JsonParser().parse(message).getAsJsonObject();
            String op = data.get("op").getAsString();
            if (op.equals("start")) {pending=data;pendingBenchmark=LabBenchmarkWorkload.configured();pendingBenchmark.verifyPeer(data);pendingAt=System.nanoTime();pendingOverlayTicks=0;}
            if(op.equals("flight-begin")&&active!=null&&active.id.equals(data.get("runId").getAsString()))active.flight=new ClientSpectatorFlight(data,active.benchmark,data.get("moving").getAsBoolean());
            if (op.equals("stop") && active != null && active.id.equals(data.get("runId").getAsString())) finish();
            if (op.equals("quit")) Minecraft.getMinecraft().shutdown();
        });
    }
    @SubscribeEvent public void movement(net.minecraftforge.client.event.InputUpdateEvent event){Minecraft mc=Minecraft.getMinecraft();if(active!=null&&event.getEntityPlayer()==mc.player){if(active.flight!=null)active.flight.input(mc,event.getMovementInput());}}
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.END && mc.world != null && mc.player != null) {
            overlays.observe(mc);
            // Twice a second: block position and yaw, so a free flight's sections can be placed against the camera.
            if (LabLodTrace.ENABLED && lodTicks++ % 10 == 0)
                LabLodTrace.event("player", 0, (long) Math.floor(mc.player.posX), (long) Math.floor(mc.player.posY), (long) Math.floor(mc.player.posZ), (long) mc.player.rotationYaw);
            String screen = mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName();
            if (!screen.equals(lastScreen)) { lastScreen = screen; System.out.println("CUBICLAB_CLIENT_VIEW screen=" + screen); }
        }
        if (event.phase == TickEvent.Phase.END && pending != null && mc.world != null && mc.player != null && mc.currentScreen == null) {
            if(pendingBenchmark.waitForOverlays&&overlays.failure==null&&overlays.active){pendingOverlayTicks++;return;}
            if(pending.get("scenario").getAsString().equals("spectator-flight"))ScreenShotHelper.saveScreenshot(mc.gameDir,"cubiclab-"+pending.get("runId").getAsString()+"-start.png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
            active = new Capture(pending,pendingBenchmark,pendingOverlayTicks,System.nanoTime()-pendingAt); pending = null; LabHooks.clientPackets = active.packets;
            LabRenderDiagnostics.beginCapture(active.id);
            LabDeliveryTrace.begin();
            LabLodTrace.mark("lab-start");
            Network.CHANNEL.sendToServer(new Network.ServerMessage(CubicLab.control("ready", active.id, active.scenario)));
        }
        if (active == null) return;
        if(event.phase==TickEvent.Phase.END&&active.flight!=null)active.flight.tick(mc,active.started,active.loads,active.unloads);
        if(event.phase==TickEvent.Phase.END&&mc.player!=null&&active.scenario.equals("route")&&active.routeTicks++%20==0){
            Map<String,Object> row=new LinkedHashMap<>();row.put("nanos",System.nanoTime()-active.started);
            row.put("pose",new double[]{mc.player.posX,mc.player.posY,mc.player.posZ,mc.player.rotationYaw,mc.player.rotationPitch});
            if(active.routeSamples.size()<1200)active.routeSamples.add(row);
            else active.droppedRouteSamples++;
        }
        if (event.phase == TickEvent.Phase.END && active.fullView != null) active.fullView.tick(mc, active.id, active.scenario);
        if (event.phase == TickEvent.Phase.START) active.tickStarted = System.nanoTime();
        else if (active.tickStarted != 0) { active.ticks.add(System.nanoTime() - active.tickStarted); active.tickStarted = 0; }
    }
    @SubscribeEvent public void render(TickEvent.RenderTickEvent event) {
        if (active == null) return;
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START) {
            if (active.lastFrame != 0) active.frames.add(now - active.lastFrame);
            active.lastFrame = now; active.renderStarted = now;
            if (Minecraft.getMinecraft().currentScreen != null) active.guiFrames++;
            if(overlays.active)active.overlayFrames++;
            if(active.fullView!=null)active.fullView.renderStart(Minecraft.getMinecraft());
        } else if (active.renderStarted != 0) {
            active.render.add(now - active.renderStarted); active.renderStarted = 0;
            if(active.fullView!=null)active.fullView.render(Minecraft.getMinecraft());
            LabRenderDiagnostics.frame();
            if(active.flight!=null)active.coverage.frame(Minecraft.getMinecraft(),active.id,active.flight.moving&&!active.flight.done);
        }
    }
    @SubscribeEvent public void load(CubeEvent.Load event) {
        if (active != null && event.getWorld().isRemote) {
            if(active.fullView!=null)active.fullView.dataChanged();
            LabRenderReadyHooks.dataChanged(event.getWorld());
            active.loads++; int y = event.getCube().getY();
            LabDeliveryTrace.event("client-load-event",event.getCube().getX(),y,event.getCube().getZ(),0,0);
            active.loadedByY.put(y, active.loadedByY.getOrDefault(y, 0L) + 1);
            io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld world = (io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld) event.getWorld();
            if (y < (world.getMinHeight() >> 4) || y >= (world.getMaxHeight() >> 4)) active.outsideHeightLoads++;
        }
    }
    @SubscribeEvent public void unload(CubeEvent.Unload event) { if (active != null && event.getWorld().isRemote) {LabDeliveryTrace.event("client-unload-event",event.getCube().getX(),event.getCube().getY(),event.getCube().getZ(),0,0);active.unloads++;if(active.fullView!=null)active.fullView.dataChanged();LabRenderReadyHooks.dataChanged(event.getWorld());} }
    @SubscribeEvent public void overlay(RenderGameOverlayEvent.Text event) {
        event.getLeft().add("Cubic Lab: " + (active == null ? last : active.scenario + " / " + active.id.substring(0, 8)));
        if (active != null) event.getLeft().add("Received cubes: " + active.packets.cubes.get() + " / " + active.packets.bytes.get() / 1024 + " KiB payload");
    }
    private void finish() {
        Minecraft mc = Minecraft.getMinecraft(); Capture capture = active; active = null; LabHooks.clientPackets = null;
        LabLodTrace.mark("lab-finish");
        long finished=System.nanoTime();String finishedUtc=Instant.now().toString();
        if(capture.fullView!=null)capture.fullView.close();
        Map<String, Object> rendererDiagnostics = LabRenderDiagnostics.finishCapture();
        long[] gc = LabData.gc();
        Map<String, Object> result = new LinkedHashMap<>();
        if(LabDeliveryTrace.ENABLED)result.put("deliveryTrace",LabDeliveryTrace.finish(mc.gameDir.toPath().resolve("cubiclab-results").resolve(capture.id)));
        result.put("threadCosts", capture.threadCosts.finish());
        result.put("schema", "cubiclab.client.v1"); result.put("runId", capture.id); result.put("scenario", capture.scenario);
        if(capture.benchmark.identity!=null)result.put("benchmarkWorkload",capture.benchmark.identity);
        if(capture.scenario.equals("route")){
            LabData.write(mc.gameDir.toPath().resolve("cubiclab-results").resolve(capture.id).resolve("client-route-camera.json"),capture.routeSamples);
            result.put("routeCameraSamples",capture.routeSamples.size());result.put("routeCameraSamplesDropped",capture.droppedRouteSamples);
            result.put("routeCameraTrace","client-route-camera.json");
            result.put("finalCameraPose",new double[]{mc.player.posX,mc.player.posY,mc.player.posZ,mc.player.rotationYaw,mc.player.rotationPitch});
        }
        result.put("runtime", LabData.runtime()); result.put("startUtc", capture.startUtc); result.put("endUtc", finishedUtc);
        result.put("elapsedNanos", finished - capture.started); result.put("frameIntervals", capture.frames.summary());
        result.put("renderEventWork", capture.render.summary()); result.put("clientTicks", capture.ticks.summary());
        result.put("packets", capture.packets.summary()); result.put("cubeLoadEvents", capture.loads);
        result.put("rendererDiagnostics", rendererDiagnostics);
        result.put("cubeUnloadEvents", capture.unloads); result.put("observedLoadsByCubeY", capture.loadedByY);
        result.put("loadsOutsideBuildHeight", capture.outsideHeightLoads);
        result.put("framesWithGui", capture.guiFrames); result.put("renderDistance", mc.gameSettings.renderDistanceChunks);
        result.put("framesWithOverlay",capture.overlayFrames);result.put("overlayProbeFailure",overlays.failure);
        result.put("overlayObservationAvailable",overlays.failure==null);result.put("overlayWaitRequired",capture.benchmark.waitForOverlays);
        result.put("preCaptureOverlayWaitTicks",capture.overlayWaitTicks);result.put("preCaptureReadyWaitNanos",capture.readyWaitNanos);
        result.put("overlayProbeScope","Read-only vanilla title timer, sampled on client ticks. Not a detector for every mod overlay; actual captures still require review. Pre-capture waiting warms the current viewer dependencies and is excluded from active scenario timing.");
        if(capture.fullView!=null)result.put("fullView",capture.fullView.summary());
        result.put("measurementMode", LabData.measurementMode());
        result.put("nativePresentationTimingMeasured",false);
        if(capture.flight!=null)result.put("spectatorFlight",capture.flight.summary(mc));
        result.put("verticalViewDistance", io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.verticalCubeLoadDistance);
        result.put("frameCap", mc.gameSettings.limitFramerate); result.put("vsync", mc.gameSettings.enableVsync);
        result.put("fancyGraphics", mc.gameSettings.fancyGraphics); result.put("fullscreen", mc.gameSettings.fullScreen);
        result.put("width", mc.displayWidth); result.put("height", mc.displayHeight);
        result.put("glVendor", GL11.glGetString(GL11.GL_VENDOR)); result.put("glRenderer", GL11.glGetString(GL11.GL_RENDERER));
        result.put("glVersion", GL11.glGetString(GL11.GL_VERSION));
        result.put("gcCollections", gc[0] - capture.gc[0]); result.put("gcReportedMillis", gc[1] - capture.gc[1]);
        result.put("notes", "Frame intervals include cap/vsync/scheduling. Render-event work is not exclusive GPU time. Packet decode runs on network threads; apply measures main-thread cube/block/light/entity handling. Phase window includes control-message latency.");
        LabData.write(mc.gameDir.toPath().resolve("cubiclab-results").resolve(capture.id).resolve("client.json"), result);
        JsonObject report = new com.google.gson.Gson().toJsonTree(result).getAsJsonObject(); report.addProperty("op", "report");
        Network.CHANNEL.sendToServer(new Network.ServerMessage(report));
        last = capture.scenario + " finished; results saved";
        // Capture real rendered output after measurement so the screenshot cost is excluded.
        ScreenShotHelper.saveScreenshot(mc.gameDir, "cubiclab-" + capture.id + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        System.out.println("CUBICLAB_CLIENT_SAVED runId=" + capture.id);
    }
    private static final class Capture {
        final String id, scenario, startUtc = Instant.now().toString();
        final long started = System.nanoTime();
        final long[] gc = LabData.gc();
        final LabThreadCosts threadCosts = new LabThreadCosts();
        final Samples frames = new Samples(), ticks = new Samples(), render = new Samples();
        final LabHooks.PacketCounters packets = new LabHooks.PacketCounters();
        final Map<Integer, Long> loadedByY = new TreeMap<>();
        long tickStarted, renderStarted, lastFrame, loads, unloads, guiFrames, outsideHeightLoads;
        ClientSpectatorFlight flight;
        final LabMovingCoverage coverage=new LabMovingCoverage();
        final LabClientView fullView;
        final LabBenchmarkWorkload benchmark;
        final List<Map<String,Object>> routeSamples=new ArrayList<>();
        int routeTicks,droppedRouteSamples;
        final int overlayWaitTicks;
        final long readyWaitNanos;
        long overlayFrames;
        Capture(JsonObject data,LabBenchmarkWorkload benchmark,int overlayWaitTicks,long readyWaitNanos) { id = data.get("runId").getAsString(); scenario = data.get("scenario").getAsString();
            this.benchmark=benchmark;this.overlayWaitTicks=overlayWaitTicks;this.readyWaitNanos=readyWaitNanos;
            benchmark.verifyPeer(data);
            fullView=data.has("fullView")?new LabClientView(data.getAsJsonObject("fullView"),data.has("fullViewRenderReady")&&data.get("fullViewRenderReady").getAsBoolean()):null;
        }
    }
}
