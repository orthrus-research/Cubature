package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.opencubicchunks.cubicchunks.api.world.CubeEvent;
import io.github.opencubicchunks.cubicchunks.api.world.CubeDataEvent;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.*;

@Mod(modid = "cubiclab", name = "CubicChunks Lab", version = "0.1.0", dependencies = "required-after:cubicchunks;after:riven")
public final class CubicLab {
    @SidedProxy(clientSide = "io.github.opencubicchunks.cubicchunks.lab.ClientProxy", serverSide = "io.github.opencubicchunks.cubicchunks.lab.CommonProxy")
    public static CommonProxy proxy;
    static LabJob active;
    static LabPregenerator pregenerator;
    private static final Map<String, LabJob> completed = new LinkedHashMap<>();
    private long tickStarted;
    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) {
        Network.initialize(); MinecraftForge.EVENT_BUS.register(this); proxy.initialize();
        LabStorageContent.initialize();MinecraftForge.EVENT_BUS.register(new LabStorageContent());
        LabHooks.installDiagnostics();
    }
    @Mod.EventHandler public void serverStarting(FMLServerStartingEvent event) { event.registerServerCommand(new LabCommand()); }
    @Mod.EventHandler public void stopping(FMLServerStoppingEvent event) {
        if(pregenerator!=null){try{pregenerator.cancel();}catch(java.io.IOException e){throw new IllegalStateException(e);}pregenerator=null;}
        if (active != null) { active.failure = "Server stopped during test"; active.finish(); active = null; }
        LabJob.release(); LabHooks.serverPackets = null; completed.clear();
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) { tickStarted = System.nanoTime(); return; }
        if(pregenerator!=null){try{pregenerator.tick();if(pregenerator.done)pregenerator=null;}catch(Exception e){pregenerator.message("CUBICLAB_PREGEN_RESULT id="+pregenerator.id+" FAIL "+e);pregenerator=null;}return;}
        LabJob job = active;
        if (job == null) return;
        try { job.tick(); } catch (Exception failure) { job.failure = failure.toString(); job.done = true; }
        if (job.started != 0) job.ticks.add(System.nanoTime() - tickStarted);
    }
    /** The ordinary lab timer encloses the actual server tick, including every tick event callback. */
    public static Object serverTickObserver(){return active;}
    public static void serverTickCompleted(Object observer,long duration,boolean normalReturn){
        if(active==null||(observer!=null&&observer!=active))return;
        LabJob job=active;
        if(job.started!=0)job.completeTicks.add(duration);
        if(!normalReturn){job.failure="MinecraftServer.tick threw";job.done=true;}
        if (job.done) {
            job.finish(); completed.put(job.id, job); active = null;
            while (completed.size() > 32) completed.remove(completed.keySet().iterator().next());
        }
    }
    @SubscribeEvent public void cubeLoad(CubeEvent.Load event) {
        if(!event.getWorld().isRemote)LabDeliveryTrace.event("server-load-event",event.getCube().getX(),event.getCube().getY(),event.getCube().getZ(),0,0);
        if (!event.getWorld().isRemote && active != null && active.started != 0) active.cubeLoads++;
    }
    @SubscribeEvent public void cubeUnload(CubeEvent.Unload event) {
        if(!event.getWorld().isRemote)LabDeliveryTrace.event("server-unload-event",event.getCube().getX(),event.getCube().getY(),event.getCube().getZ(),0,0);
        if (!event.getWorld().isRemote && active != null && active.started != 0) active.cubeUnloads++;
    }
    @SubscribeEvent public void dataLoad(CubeDataEvent.Load event) {
        if (!event.getWorld().isRemote && active != null && active.started != 0) active.dataLoads++;
    }
    @SubscribeEvent public void dataSave(CubeDataEvent.Save event) {
        if (!event.getWorld().isRemote && active != null && active.started != 0) active.dataSaves++;
    }
    @SubscribeEvent public void worldSave(net.minecraftforge.event.world.WorldEvent.Save event) {
        if(active!=null&&active.started!=0&&event.getWorld()==active.world&&active.saveContracts!=null)active.saveContracts.worldSaved(active.server.getTickCounter());
    }
    static void receive(EntityPlayerMP player, String message) {
        JsonObject json = new JsonParser().parse(message).getAsJsonObject();
        String id = json.get("runId").getAsString();
        LabJob job = active != null && active.id.equals(id) ? active : completed.get(id);
        if (job == null || !job.peers.contains(player.getUniqueID())) return;
        String op = json.get("op").getAsString();
        if (op.equals("preparation-view") && job == active && job.preparationView != null)
            job.preparationView.client(player, json.get("loaded").getAsInt(),json);
        if(job==active&&job.spectatorLifecycle!=null){
            if(op.equals("flight-view"))job.spectatorLifecycle.clientResident=json.get("resident").getAsInt();
            if(op.equals("flight-started"))job.spectatorLifecycle.flightStarted=System.nanoTime()-job.started;
            if(op.equals("flight-finished"))job.spectatorLifecycle.clientResult=json;
        }
        if (op.equals("ready") && job == active) job.ready.add(player.getUniqueID());
        if (op.equals("report")) {
            LabData.write(job.output.resolve("client-" + player.getUniqueID() + ".json"), json);
            job.server.sendMessage(new TextComponentString("CUBICLAB_CLIENT_RESULT runId=" + id + " player=" + player.getName()));
        }
    }
    static JsonObject control(String op, String id, String scenario) {
        JsonObject data = new JsonObject(); data.addProperty("op", op); data.addProperty("runId", id);
        data.addProperty("scenario", scenario); return data;
    }
    private static final class LabCommand extends CommandBase {
        public String getName() { return "cubiclab"; }
        public String getUsage(ICommandSender sender) { return "cubiclab run idle|generate|resident|save|load-sync|load-async|horizontal|vertical|route|lighting-snapshot|scheduled-ticks|save-burst|autosave|storage-failure|storage-strategies|generation-contracts|first-light | status | release | cancel | quit"; }
        public int getRequiredPermissionLevel() { return 2; }
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if(args.length==4&&(args[0].equals("storage-crash")||args[0].equals("storage-recover"))&&args[3].equals("isolated-files")){
                if(active!=null||pregenerator!=null||!server.getPlayerList().getPlayers().isEmpty())throw new CommandException("Crash fixtures require an idle owned server with no players");
                try{io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabStorageCrash.run(server,args[1],args[2],args[0].equals("storage-recover"));}
                catch(Exception e){throw new CommandException(e.toString());}return;
            }
            if(args.length==7&&args[0].equals("pregenerate")){
                if(active!=null||pregenerator!=null)throw new CommandException("A lab job is active");
                try{pregenerator=new LabPregenerator(server,args[1],Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),Integer.parseInt(args[6]));}
                catch(Exception e){throw new CommandException(e.toString());}
                sender.sendMessage(new TextComponentString("CUBICLAB_PREGEN_STARTED id="+pregenerator.id));return;
            }
            if(pregenerator!=null){
                if(args.length==1&&args[0].equals("cancel")){try{pregenerator.cancel();pregenerator=null;}catch(java.io.IOException e){throw new CommandException(e.toString());}return;}
                if(args.length==1&&args[0].equals("status")){sender.sendMessage(new TextComponentString("Pregeneration "+pregenerator.position));return;}
                throw new CommandException("Cancel the active pregenerator first");
            }
            if (args.length == 2 && args[0].equals("run")) {
                if (active != null) throw new CommandException("A lab test is active");
                try { active = new LabJob(server, args[1]); }
                catch (Exception failure) { throw new CommandException(failure.getMessage()); }
                sender.sendMessage(new TextComponentString("CUBICLAB_STARTED runId=" + active.id + " scenario=" + args[1]));
                return;
            }
            if (args.length != 1) throw new CommandException(getUsage(sender));
            switch (args[0]) {
                case "status": sender.sendMessage(new TextComponentString(active == null ? "Cubic Lab idle" : active.scenario + " " + active.progress)); break;
                case "release": if (active != null) throw new CommandException("Cancel the active test first"); LabJob.release(); break;
                case "cancel": if (active != null) { active.failure = "Canceled by operator"; active.done = true; } break;
                case "quit":
                    if (active != null) throw new CommandException("Cancel the active test first");
                    LabJob.release();
                    for (EntityPlayerMP player : server.getPlayerList().getPlayers()) Network.CHANNEL.sendTo(new Network.ClientMessage(control("quit", "", "")), player);
                    break;
                default: throw new CommandException(getUsage(sender));
            }
        }
    }
}
