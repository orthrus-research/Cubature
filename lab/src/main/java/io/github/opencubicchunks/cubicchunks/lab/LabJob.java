package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import io.github.opencubicchunks.cubicchunks.core.util.ticket.ITicket;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Timed work includes qualification witnesses and explicit save/flush stages; ordinary full-view streaming never forces them. */
final class LabJob {
    private static final ITicket RETAIN = () -> false;
    private static final Map<CubePos, Cube> held = new LinkedHashMap<>();
    final MinecraftServer server;
    final WorldServer world;
    final CubeProviderServer provider;
    final String scenario, id = UUID.randomUUID().toString();
    final Path output;
    final Set<UUID> peers = new HashSet<>(), ready = new HashSet<>();
    final List<CubePos> targets = new ArrayList<>();
    final Map<CubePos, java.util.function.Consumer<Cube>> subscriptions = new HashMap<>();
    final Samples ticks = new Samples(), operations = new Samples();
    final Samples completeTicks = new Samples();
    final LabHooks.PacketCounters packets = new LabHooks.PacketCounters();
    final LabStageTimings stages = new LabStageTimings();
    final LabBenchmarkWorkload benchmark = LabBenchmarkWorkload.configured();
    final List<Map<String,Object>> routeTrace = new ArrayList<>();
    final LabViewGeometry fullView;
    final int fullViewTimeoutSeconds;
    final int streamTicks = Integer.getInteger("cubiclab.streamTicks", 240);
    /** Riven's former world bounds: fixtures, routes and views stay in this band, so results stay comparable while the world extends beyond it. */
    static final int BAND_MIN_Y = -128, BAND_MAX_Y = 384;
    final int minY, maxY, worldMinY, worldMaxY, initialCubes;
    final long created = System.nanoTime();
    long started, ended, operationCount, dataLoads, dataSaves, cubeLoads, cubeUnloads, flushNanos, submissionNanos, lookupChecksum;
    long[] gcStart;
    LabThreadCosts threadCosts;
    String startUtc, failure;
    int warmup = 40, progress, elapsedTicks, outstanding, completedAsync;
    int captureStartLoadedCubes;
    boolean controlSent, done;
    LabScheduledTicks scheduledFixture;
    LabSavePacing savePacingFixture;
    LabSaveContracts saveContracts;
    Map<String,Object> storageFaultMatrix;
    Map<String,Object> storageContent;
    io.github.opencubicchunks.cubicchunks.core.server.LabLightingSaveProbe lightingSaveFixture;
    LabTwoWorlds twoWorlds;
    LabFirstLight firstLightFixture;
    LabPreparationView preparationView;
    LabSpectatorLifecycle spectatorLifecycle;
    io.github.opencubicchunks.cubicchunks.core.server.LabGenerationProbe generationFixture;
    io.github.opencubicchunks.cubicchunks.core.server.LabMapChurnProbe mapChurnFixture;
    io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabStorageFailureProbe storageFailureFixture;
    io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabStorageStrategies storageStrategiesFixture;
    io.github.opencubicchunks.cubicchunks.core.server.LabMaintenanceProbe maintenanceFixture;

    LabJob(MinecraftServer server, String scenario) {
        if (!Arrays.asList("idle", "generate", "resident", "save", "load-sync", "load-async", "horizontal", "vertical", "lighting-snapshot", "lighting-save-order", "scheduled-ticks", "save-burst", "autosave", "storage-failure", "storage-strategies", "map-churn", "generation-contracts", "first-light").contains(scenario))
            if(!scenario.equals("route")&&!scenario.equals("full-view")&&!scenario.equals("maintenance-contracts")&&!Arrays.asList("spectator-flight","lifecycle-settle","lifecycle-save-purge","save-command","save-clean","autosave-natural","storage-fault-matrix","storage-content-write","storage-content-read","storage-two-worlds").contains(scenario))throw new IllegalArgumentException("Unknown lab scenario");
        this.server = server; this.scenario = scenario; world = server.getWorld(0);
        if(benchmark.identity!=null&&world.getSeed()!=benchmark.seed)throw new IllegalArgumentException("Native world seed differs from benchmark workload");
        if(scenario.equals("route")&&benchmark.routeTicks==0)throw new IllegalArgumentException("Route requires a prepared workload with explicit waypoints");
        warmup=benchmark.warmupTicks;
        fullView = scenario.equals("full-view") ? LabViewGeometry.configured() : null;
        fullViewTimeoutSeconds = Integer.getInteger("cubiclab.fullViewTimeoutSeconds", 840);
        if (streamTicks < 240 || streamTicks > 12000) throw new IllegalArgumentException("Streaming observation length must be 240..12000 ticks");
        if (fullViewTimeoutSeconds < 30 || fullViewTimeoutSeconds > 3600) throw new IllegalArgumentException("Full-view timeout must be 30..3600 seconds");
        ICubicWorld cubic = (ICubicWorld) world;
        if (!cubic.isCubicWorld()) throw new IllegalArgumentException("Lab requires a cubic overworld");
        worldMinY = cubic.getMinHeight(); worldMaxY = cubic.getMaxHeight();
        if (worldMinY > BAND_MIN_Y || worldMaxY < BAND_MAX_Y) throw new IllegalArgumentException("World bounds [" + worldMinY + "," + worldMaxY + ") must contain the lab band [" + BAND_MIN_Y + "," + BAND_MAX_Y + ")");
        minY = BAND_MIN_Y; maxY = BAND_MAX_Y;
        provider = (CubeProviderServer) world.getChunkProvider(); initialCubes = provider.getLoadedCubeCount();
        output = server.getFile("cubiclab-results").toPath().resolve(id);
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player.dimension == 0) peers.add(player.getUniqueID());
        }
        if ((scenario.equals("route") || scenario.equals("horizontal") || scenario.equals("vertical") || scenario.equals("map-churn")) && peers.isEmpty())
            throw new IllegalArgumentException("Streaming tests require an in-game player");
        // Two separate cohorts permit independent process-reload sync and async tests.
        for (int cohort = 0; cohort < 2; cohort++) {
            if (scenario.equals("load-sync") && cohort != 0 || scenario.equals("load-async") && cohort != 1) continue;
            for (int x = benchmark.originX + cohort * benchmark.offsetX; x < benchmark.originX + cohort * benchmark.offsetX + benchmark.width; x++)
                for (int z = benchmark.originZ + cohort * benchmark.offsetZ; z < benchmark.originZ + cohort * benchmark.offsetZ + benchmark.depth; z++)
                    for (int y = minY >> 4; y < maxY >> 4; y++) targets.add(new CubePos(x, y, z));
        }
        if (scenario.startsWith("load-")) for (CubePos pos : targets) {
            if (provider.getLoadedCube(pos) != null) throw new IllegalArgumentException("Reload cohort already resident; restart and keep players at spawn");
        }
        if (Arrays.asList("resident", "save", "first-light", "lighting-snapshot", "lighting-save-order", "scheduled-ticks", "save-burst", "autosave", "storage-failure", "storage-strategies", "save-command", "save-clean", "autosave-natural").contains(scenario)) for (CubePos pos : targets) {
            if (provider.getLoadedCube(pos) == null) throw new IllegalArgumentException("Generate the fixture first");
        }
    }
    void tick() throws java.io.IOException {
        if (done) return;
        long deadlineNanos = deadlineNanos();
        if (System.nanoTime() - created > deadlineNanos) { failure = "Scenario timed out after effective job deadline " + deadlineNanos / 1000000000L + " seconds"; done = true; return; }
        if (warmup-- > 0) return;
        if (scenario.equals("first-light") && firstLightFixture == null) {
            firstLightFixture = new LabFirstLight(); firstLightFixture.prepare(this);
        }
        if ((scenario.equals("autosave") || scenario.equals("save-burst")) && savePacingFixture == null) {
            savePacingFixture = new LabSavePacing(); savePacingFixture.prepare(this);
        }
        if (scenario.equals("storage-strategies") && storageStrategiesFixture == null) {
            saveAndFlush("preflightStorage", false);
            List<Cube> sources = new ArrayList<>();
            for (CubePos pos : targets) sources.add(provider.getLoadedCube(pos));
            java.nio.file.Files.createDirectories(output);
            storageStrategiesFixture = new io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabStorageStrategies(
                    output.resolve("strategy-storage"), sources);
        }
        if (!controlSent) {
            if(Arrays.asList("save-command","save-clean","autosave-natural").contains(scenario)){
                saveContracts=new LabSaveContracts(scenario);saveContracts.prepare(this);
            }
            JsonObject control=CubicLab.control("start",id,scenario);
            if(benchmark.identity!=null)control.add("benchmarkWorkload",new com.google.gson.Gson().toJsonTree(benchmark.identity));
            if(fullView!=null){control.add("fullView",fullView.json());control.addProperty("fullViewRenderReady",Boolean.getBoolean("cubiclab.fullViewRenderReady"));}
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) if (peers.contains(player.getUniqueID()))
                Network.CHANNEL.sendTo(new Network.ClientMessage(control), player);
            controlSent = true;
        }
        if (!ready.containsAll(peers)) return;
        if (started == 0) { LabStorageTrace.start(); LabDeliveryTrace.begin(); LabLodTrace.mark("lab-start");captureStartLoadedCubes=provider.getLoadedCubeCount();started = System.nanoTime(); startUtc = Instant.now().toString(); gcStart = LabData.gc(); threadCosts = new LabThreadCosts(); LabHooks.serverPackets = packets; }
        elapsedTicks++;
        switch (scenario) {
            case "spectator-flight":case "lifecycle-settle":case "lifecycle-save-purge":
                if(spectatorLifecycle==null)spectatorLifecycle=new LabSpectatorLifecycle(this);
                done=spectatorLifecycle.tick(this);break;
            case "storage-two-worlds":
                if (twoWorlds == null) twoWorlds = new LabTwoWorlds();
                try { done = twoWorlds.tick(this); } catch (Exception error) { throw new java.io.IOException("Two-world fixture failed", error); }
                if (done) operationCount = 2;
                break;
            case "storage-content-write":case "storage-content-read":
                storageContent=LabStorageContent.run(this,scenario.equals("storage-content-write"));done=true;break;
            case "storage-fault-matrix":
                storageFaultMatrix=new io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabStorageFaultMatrix(output.resolve("isolated-fault-matrix")).summary();
                operationCount=7;done=true;break;
            case "save-command": case "save-clean": case "autosave-natural": done=saveContracts.tick(this);break;
            case "full-view":
                if (preparationView == null) preparationView = new LabPreparationView(this);
                done = preparationView.tick(this);
                if (done) operationCount = 15625;
                break;
            case "first-light":
                done = firstLightFixture.tick(this);
                break;
            case "generation-contracts":
                if (generationFixture == null) {
                    generationFixture = new io.github.opencubicchunks.cubicchunks.core.server.LabGenerationProbe(provider);
                    generationFixture.prepare();
                }
                done = generationFixture.complete();
                if (done) operationCount = generationFixture.operations();
                break;
            case "maintenance-contracts":
                if (maintenanceFixture == null) {
                    maintenanceFixture = new io.github.opencubicchunks.cubicchunks.core.server.LabMaintenanceProbe(provider);
                    maintenanceFixture.prepare();
                }
                done = maintenanceFixture.tick();
                if (done) operationCount = maintenanceFixture.operations();
                break;
            case "map-churn":
                if (mapChurnFixture == null) mapChurnFixture = new io.github.opencubicchunks.cubicchunks.core.server.LabMapChurnProbe();
                List<EntityPlayerMP> participants = new ArrayList<>();
                for (EntityPlayerMP player : server.getPlayerList().getPlayers()) if (peers.contains(player.getUniqueID())) participants.add(player);
                done = mapChurnFixture.observe(world, participants, elapsedTicks);
                if (done) operationCount = 8;
                break;
            case "storage-strategies":
                done = storageStrategiesFixture.observe();
                if (done) operationCount = 21L * targets.size();
                break;
            case "idle": done = elapsedTicks >= benchmark.idleTicks; break;
            case "save-burst": case "autosave":
                done = savePacingFixture.tick(this); break;
            case "storage-failure":
                if (storageFailureFixture == null) storageFailureFixture = new io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabStorageFailureProbe(
                    output.resolve("fault-storage"), provider.getLoadedCube(targets.get(0)));
                done = storageFailureFixture.observe();
                if (done) operationCount = 2;
                break;
            case "scheduled-ticks":
                if (scheduledFixture == null) {
                    scheduledFixture = new LabScheduledTicks(world);
                    scheduledFixture.prepare(this);
                }
                done = scheduledFixture.observe(this);
                if (!done && elapsedTicks >= 80) throw new IllegalStateException("Scheduled vanilla updates did not finish: " + scheduledFixture.summary());
                break;
            case "lighting-save-order":
                if (lightingSaveFixture == null) lightingSaveFixture = new io.github.opencubicchunks.cubicchunks.core.server.LabLightingSaveProbe(provider);
                done = lightingSaveFixture.tick();
                if (done) operationCount = 8;
                break;
            case "lighting-snapshot":
                for (CubePos pos : Arrays.asList(targets.get(0), targets.get(targets.size() - 1))) {
                    long snapshotStart = System.nanoTime();
                    io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabLightingSnapshotProbe.verify(provider.getLoadedCube(pos));
                    operationCount++; operations.add(System.nanoTime() - snapshotStart);
                }
                done = true; break;
            case "resident":
                long before = System.nanoTime();
                for (int i = 0; i < 10000; i++) {
                    CubePos pos = targets.get((int) ((operationCount + i) % targets.size()));
                    Cube cube = provider.getLoadedCube(pos);
                    if (cube == null) throw new IllegalStateException("Resident cube disappeared");
                    lookupChecksum += cube.getY();
                }
                operations.add(System.nanoTime() - before); operationCount += 10000;
                done = elapsedTicks >= benchmark.residentTicks; break;
            case "route":
                int routeTick=elapsedTicks-1;
                double[] routePose=benchmark.position(routeTick);
                for(UUID peer:peers){
                    EntityPlayerMP player=server.getPlayerList().getPlayerByUUID(peer);
                    if(player==null)throw new IllegalStateException("Route participant disconnected");
                    if(benchmark.moveAt(routeTick)){player.setGameType(GameType.SPECTATOR);
                        ((io.github.opencubicchunks.cubicchunks.core.server.PlayerCubeMap)world.getPlayerChunkMap()).arrivals().teleportNow(player,routePose[0],routePose[1],routePose[2],(float)routePose[3],(float)routePose[4]);operationCount++;}
                    if(Math.abs(player.posX-routePose[0])>.3||Math.abs(player.posY-routePose[1])>.3||Math.abs(player.posZ-routePose[2])>.3
                            ||Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(player.rotationYaw-(float)routePose[3]))>2||Math.abs(player.rotationPitch-routePose[4])>2)
                        throw new IllegalStateException("Route participant moved outside the declared pose");
                }
                if(routeTick==0||benchmark.waypoint(routeTick)!=benchmark.waypoint(routeTick-1)||elapsedTicks%20==0||elapsedTicks==benchmark.routeTicks)
                    for(UUID peer:peers){EntityPlayerMP player=server.getPlayerList().getPlayerByUUID(peer);
                        if(player==null)throw new IllegalStateException("Route participant disconnected");
                        Map<String,Object> row=new LinkedHashMap<>();row.put("tick",routeTick);row.put("waypoint",benchmark.waypoint(routeTick));
                        row.put("nanos",System.nanoTime()-started);row.put("player",peer);row.put("pose",new double[]{player.posX,player.posY,player.posZ,player.rotationYaw,player.rotationPitch});routeTrace.add(row);
                    }
                done=elapsedTicks>=benchmark.routeTicks;break;
            case "horizontal": case "vertical":
                if ((elapsedTicks - 1) % 40 == 0) {
                    int step = (elapsedTicks - 1) / 40;
                    for (EntityPlayerMP player : server.getPlayerList().getPlayers()) if (peers.contains(player.getUniqueID())) {
                        player.setGameType(GameType.SPECTATOR);
                        double x = scenario.equals("horizontal") ? 2048 + step * 256 : 2048;
                        double y = scenario.equals("vertical") ? LabRoute.height(minY, maxY, step) : 80;
                        // Scripted moves keep their synchronous arrival, as these streams were measured.
                        ((io.github.opencubicchunks.cubicchunks.core.server.PlayerCubeMap) world.getPlayerChunkMap()).arrivals().teleportNow(player, x + .5, y, 2048.5, 0, 15);
                    }
                    progress++; operationCount++;
                }
                done = elapsedTicks >= streamTicks; break;
            case "load-async":
                while (progress < targets.size() && outstanding < benchmark.asyncConcurrency) {
                    CubePos pos = targets.get(progress++); outstanding++;
                    long requestStarted = System.nanoTime();
                    java.util.function.Consumer<Cube> consumer = cube -> {
                        subscriptions.remove(pos);
                        if (done) return;
                        outstanding--; completedAsync++; operationCount++;
                        try { checkCube(cube, pos, true); retain(cube); }
                        catch (Exception error) { failure = error.toString(); }
                        operations.add(System.nanoTime() - requestStarted);
                    };
                    subscriptions.put(pos, consumer);
                    provider.asyncGetCube(pos.getX(), pos.getY(), pos.getZ(), Requirement.LOAD, consumer);
                }
                done = completedAsync == targets.size(); break;
            default:
                for (int count = 0; count < benchmark.batchSize && progress < targets.size(); count++) {
                    CubePos pos = targets.get(progress++);
                    long begin = System.nanoTime();
                    if (scenario.equals("save")) {
                        Cube cube = provider.getLoadedCube(pos);
                        checkCube(cube, pos, false);
                        // Deliberate marker updates are fixture preparation, outside submission timing.
                        cube.setBlockState(marker(pos), Blocks.GOLD_BLOCK.getDefaultState()); cube.markDirty();
                        begin = System.nanoTime(); provider.getCubeIO().saveCube(cube);
                        long cost = System.nanoTime() - begin; submissionNanos += cost; operations.add(cost);
                    } else {
                        Cube cube = provider.getCubeNow(pos.getX(), pos.getY(), pos.getZ(),
                            scenario.equals("generate") ? Requirement.valueOf(benchmark.requirement) : Requirement.LOAD);
                        checkCube(cube, pos, scenario.equals("load-sync")); retain(cube);
                        operations.add(System.nanoTime() - begin);
                    }
                    operationCount++;
                }
                if (progress == targets.size()) {
                    if (scenario.equals("save")) {
                        saveAndFlush("fixture", true);
                    }
                    done = true;
                }
        }
    }
    private long deadlineNanos(){
        if(benchmark.identity!=null)return benchmark.timeoutSeconds*1000000000L;
        return fullView!=null?(fullViewTimeoutSeconds+60L)*1000000000L
                :scenario.equals("horizontal")||scenario.equals("vertical")?streamTicks*150000000L+60000000000L
                :180000000000L;
    }
    private static BlockPos marker(CubePos pos) { return new BlockPos(pos.getX() * 16 + 8, pos.getY() * 16 + 8, pos.getZ() * 16 + 8); }
    private void checkCube(Cube cube, CubePos pos, boolean saved) {
        if (cube == null || !cube.getCoords().equals(pos)) throw new IllegalStateException("Missing/wrong cube " + pos);
        if (saved && cube.getBlockState(marker(pos)).getBlock() != Blocks.GOLD_BLOCK) throw new IllegalStateException("Stored marker missing " + pos);
    }
    static void retain(Cube cube) {
        cube.getTickets().add(RETAIN); held.put(cube.getCoords(), cube);
    }
    static void release() { for (Cube cube : held.values()) cube.getTickets().remove(RETAIN); held.clear(); }
    void finish() {
        done = true;
        if (lightingSaveFixture != null) {
            try { lightingSaveFixture.close(); }
            catch (Exception error) {
                failure = (failure == null ? "" : failure + "; ") + "Lighting fixture cleanup failed: " + error;
            }
        }
        if (maintenanceFixture != null) {
            try { maintenanceFixture.close(); }
            catch (RuntimeException error) { failure = "Maintenance fixture cleanup failed: " + error; }
        }
        for (Map.Entry<CubePos, java.util.function.Consumer<Cube>> subscription : new ArrayList<>(subscriptions.entrySet())) {
            CubePos pos = subscription.getKey();
            io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge.AsyncWorldIOExecutor.dropQueuedCubeLoad(
                world, pos.getX(), pos.getY(), pos.getZ(), subscription.getValue());
        }
        subscriptions.clear();
        ended = System.nanoTime(); LabHooks.serverPackets = null;
        long[] gcEnd = LabData.gc();
        Map<String, Object> data = new LinkedHashMap<>();
        if(LabDeliveryTrace.ENABLED)data.put("deliveryTrace",LabDeliveryTrace.finish(output));
        if(started!=0)LabLodTrace.mark("lab-finish");
        if (threadCosts != null) data.put("threadCosts", threadCosts.finish());
        data.put("schema", "cubiclab.server.v1"); data.put("runId", id); data.put("scenario", scenario);
        if(benchmark.identity!=null){data.put("benchmarkWorkload",benchmark.identity);data.put("fixtureDefinition",benchmark.data.get("fixture"));}
        data.put("worldSeed",world.getSeed());
        if(scenario.equals("route")){
            data.put("routeDefinition",benchmark.data.get("route"));data.put("routeTrace",routeTrace);
            data.put("routeObservationTicks",benchmark.routeTicks);
            data.put("routeScope","Timed spectator position commands (step or per-tick interpolation), not survival traversal. Server trace is authoritative pose, not client residency or completed view. Fixed-window movement measures offered load; qualify completion separately with full-view.");
        }
        data.put("passed", failure == null); data.put("failure", failure); data.put("runtime", LabData.runtime());
        data.put("startUtc", startUtc); data.put("endUtc", Instant.now().toString());
        data.put("elapsedNanos", started == 0 ? 0 : ended - started); data.put("operations", operationCount);
        data.put("minY", minY); data.put("maxYExclusive", maxY); data.put("verticalCubesPerColumn", (maxY - minY) / 16);
        data.put("worldMinY", worldMinY); data.put("worldMaxYExclusive", worldMaxY);
        data.put("initialLoadedCubes", initialCubes); data.put("finalLoadedCubes", provider.getLoadedCubeCount());
        data.put("captureStartLoadedCubes",captureStartLoadedCubes);
        data.put("retainedFixtureCubes", held.size()); data.put("serverTicks", ticks.summary()); data.put("operationDurations", operations.summary());
        data.put("completeServerTicks",completeTicks.summary());
        data.put("completeServerTickScope","MinecraftServer.tick invocations overlapping active capture, including the whole first/final tick and all tick event callbacks. Cleanroom periodic saves are also inside the Forge event interval. Lab report serialization after the invocation is excluded. Compare this metric only with the same boundary, not historical Forge-event serverTicks.");
        data.put("saveSubmissionNanos", submissionNanos); data.put("blockingFlushNanos", flushNanos);
        data.put("stageTimings", stages.summary());
        Map<String,Object> storageTrace=LabStorageTrace.finish();if(storageTrace!=null)data.put("storageDiagnostics",storageTrace);
        if(saveContracts!=null)data.put("saveContractChecks",saveContracts.summary());
        if(spectatorLifecycle!=null)data.put("spectatorLifecycle",spectatorLifecycle.summary());
        if(storageFaultMatrix!=null)data.put("storageFaultMatrix",storageFaultMatrix);
        if(storageContent!=null)data.put("storageContent",storageContent);
        if(twoWorlds!=null)data.put("storageTwoWorlds",twoWorlds.summary());
        data.put("measurementMode", LabData.measurementMode());
        data.put("effectiveScenarioDeadlineSeconds",deadlineNanos()/1000000000.0);
        if (scenario.equals("horizontal") || scenario.equals("vertical")) data.put("streamObservationTicks",streamTicks);
        data.put("tickTimerScope","Forge ServerTick START NORMAL to END NORMAL, including lab work and CC START LOWEST completion service; excludes server-loop work outside these events. Raw completion observations allow JFR stage correlation.");
        data.put("lookupChecksum", lookupChecksum); data.put("cubeLoadEvents", cubeLoads); data.put("cubeUnloadEvents", cubeUnloads);
        if (scheduledFixture != null) {
            data.put("scheduledTickChecks", scheduledFixture.summary()); scheduledFixture.restore();
        }
        data.put("cubeDataLoadEvents", dataLoads); data.put("cubeDataSaveEvents", dataSaves); data.put("packets", packets.summary());
        if (savePacingFixture != null) data.put("savePacingChecks", savePacingFixture.summary());
        if (maintenanceFixture != null) data.put("maintenanceChecks", maintenanceFixture.summary());
        if (preparationView != null) data.put("preparationView", preparationView.summary());
        if (lightingSaveFixture != null) data.put("lightingSaveChecks", lightingSaveFixture.summary());
        if (firstLightFixture != null) data.put("firstLightChecks", firstLightFixture.summary());
        if (mapChurnFixture != null) data.put("mapChurnChecks", mapChurnFixture.summary());
        if (generationFixture != null) {
            data.put("generationChecks", generationFixture.summary()); generationFixture.close();
        }
        if (storageFailureFixture != null) {
            data.put("storageFailureChecks", storageFailureFixture.summary());
            try { storageFailureFixture.close(); }
            catch (java.io.IOException error) { data.put("passed", false); data.put("failure", error.toString()); failure = error.toString(); }
        }
        if (storageStrategiesFixture != null) {
            try { storageStrategiesFixture.close(); }
            catch (java.io.IOException error) { data.put("passed", false); data.put("failure", error.toString()); failure = error.toString(); }
            data.put("storageStrategyChecks", storageStrategiesFixture.summary());
        }
        data.put("participants", peers); data.put("horizontalViewDistance", server.getPlayerList().getViewDistance());
        data.put("networkReadTimeoutSeconds",net.minecraftforge.fml.common.network.internal.FMLNetworkHandler.READ_TIMEOUT);
        data.put("verticalViewDistance", ((io.github.opencubicchunks.cubicchunks.core.server.ICubicPlayerList) server.getPlayerList()).getVerticalViewDistance());
        data.put("configuredClientVerticalViewDistance", CubicChunksConfig.verticalCubeLoadDistance);
        Map<String, Object> packetPolicy = new LinkedHashMap<>();
        packetPolicy.put("maxCubePacketCubes", LabPacketPolicy.optionalInt(CubicChunksConfig.class, "maxCubePacketCubes"));
        packetPolicy.put("maxCubePacketBytes", CubicChunksConfig.maxCubePacketBytes);
        packetPolicy.put("maxCubeSendTimeMillis", CubicChunksConfig.maxCubeSendTimeMillis);
        packetPolicy.put("maxCubeSendBytesPerTick", CubicChunksConfig.maxCubeSendBytesPerTick);
        data.put("packetPolicy", packetPolicy);
        data.put("gcCollections", gcStart == null ? 0 : gcEnd[0] - gcStart[0]);
        data.put("gcReportedMillis", gcStart == null ? 0 : gcEnd[1] - gcStart[1]);
        data.put("notes", "Operation durations are synchronous calls or async request-to-callback latency. Resident durations cover batches of 10000 lookups. Flush explicitly blocks. Reload means a new JVM, not an OS disk-cache purge.");
        LabData.write(output.resolve("server.json"), data);
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) if (peers.contains(player.getUniqueID()))
            Network.CHANNEL.sendTo(new Network.ClientMessage(CubicLab.control("stop", id, scenario)), player);
        server.sendMessage(new TextComponentString("CUBICLAB_SERVER_RESULT runId=" + id + " scenario=" + scenario + " " + (failure == null ? "PASS" : "FAIL")));
    }
    void saveAndFlush(String scope, boolean insideCapture) throws java.io.IOException {
        long begin=System.nanoTime();
        try { provider.saveChunks(true); }
        finally { long cost=System.nanoTime()-begin; if(insideCapture)submissionNanos+=cost; stages.record(scope+"SaveSubmission",begin); }
        begin=System.nanoTime();
        try { provider.getCubeIO().flush(); }
        finally { long cost=System.nanoTime()-begin; if(insideCapture)flushNanos+=cost; stages.record(scope+"DurabilityFlush",begin); }
    }
}
