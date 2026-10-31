package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.world.CubeEvent;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.server.delivery.PreviewPool;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Optional server-side adapter. No DH classes resolve when the mod is absent. It binds a cubic preview generator to
 * each cubic level, opens the server's preview pool with the first and closes it with the last, and runs the pool's
 * governor at the end of each server tick.
 */
public final class DistantHorizonsServer extends DhApiLevelLoadEvent {
    private final Map<World,Map<Chunk,Long>> dirty=new IdentityHashMap<>();
    @Nullable private PreviewPool pool;
    private int generators;
    public static void init() {
        DistantHorizonsServer bridge=new DistantHorizonsServer();
        DhApi.events.bind(DhApiLevelLoadEvent.class,bridge);
        DistantHorizonsCompat.installServer(bridge::changed);
        MinecraftForge.EVENT_BUS.register(bridge);
    }
    @Override public void onLevelLoad(DhApiEventParam<EventParam> event) {
        Object raw=event.value.levelWrapper.getWrappedMcObject();
        if (!(raw instanceof WorldServer) || !((ICubicWorld)raw).isCubicWorld()) return;
        WorldServer world=(WorldServer)raw;
        ICubeGenerator generator=((ICubicWorldServer)world).getCubeGenerator();
        ICubicTerrainPreview provider=generator instanceof ICubicTerrainPreview?(ICubicTerrainPreview)generator:null;
        PreviewPool shared=openPool();
        DhApiResult<Void> result=DhApi.worldGenOverrides.registerWorldGeneratorOverride(event.value.levelWrapper,
                new CubicTerrainPreviewGenerator(provider,(ILevelWrapper)event.value.levelWrapper,shared,this::generatorClosed));
        if (!result.success) {
            generatorClosed();
            throw new IllegalStateException("Unable to register cubic DH generator: "+result.message);
        }
        CubicChunks.LOGGER.info("CC/DH server generator bound: dimension={} provider={} height=[{},{}).",
                world.provider.getDimension(),provider==null?"UNSUPPORTED":generator.getClass().getName(),
                ((ICubicWorld)world).getMinHeight(),((ICubicWorld)world).getMaxHeight());
    }
    /** The server's preview pool, opened with the first generator; DH binds levels on the server thread. */
    @Nullable private synchronized PreviewPool openPool() {
        if (generators++==0) pool=PreviewPool.open();
        return pool;
    }
    /** Closes the pool with the last generator, when DH unloads its level. */
    private synchronized void generatorClosed() {
        if (--generators>0 || pool==null) return;
        pool.close();
        pool=null;
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        PreviewPool current=pool;
        if (event.phase!=TickEvent.Phase.END || current==null) return;
        MinecraftServer server=FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server!=null) current.govern(System.nanoTime(),server.tickTimeArray,server.getTickCounter());
    }
    private void changed(Chunk column) {
        World world=column.getWorld();
        if (world.isRemote || !((ICubicWorld)world).isCubicWorld()) return;
        // Vanilla save callbacks may originate on an I/O worker. Never read cubes there.
        if (!((WorldServer)world).isCallingFromMinecraftThread()) {
            ((WorldServer)world).addScheduledTask(() -> changed(column));return;
        }
        dirty.computeIfAbsent(world,key -> new LinkedHashMap<>()).put(column,world.getTotalWorldTime());
    }
    @SubscribeEvent public void unloaded(WorldEvent.Unload event) { dirty.remove(event.getWorld()); }
    @SubscribeEvent public void loaded(CubeEvent.Load event) {
        if (!event.getWorld().isRemote) DistantHorizonsCompat.columnLoaded(event.getCube().getColumn());
    }
    @SubscribeEvent public void tick(TickEvent.WorldTickEvent event) {
        if (event.phase!=TickEvent.Phase.END || event.world.isRemote) return;
        Map<Chunk,Long> columns=dirty.get(event.world);
        if (columns==null || columns.isEmpty()) return;
        ILevelWrapper level=ProxyUtil.getLevelWrapper(event.world);
        if (level==null || level.getDhLevel()==null) return;
        long begin=System.nanoTime();int count=0;
        Iterator<Map.Entry<Chunk,Long>> it=columns.entrySet().iterator();
        while (it.hasNext() && count<2 && System.nanoTime()-begin<2_000_000L) {
            Map.Entry<Chunk,Long> entry=it.next();Chunk column=entry.getKey();
            if (event.world.getChunkProvider().getLoadedChunk(column.x,column.z)!=column) { it.remove();continue; }
            if (event.world.getTotalWorldTime()-entry.getValue()<3 || SharedApi.isChunkAtChunkPosAlreadyUpdating(level,column.x,column.z)) continue;
            CubicChunkSnapshot snapshot=CubicChunkSnapshot.capture(column,level);
            if (snapshot==null) continue;
            SharedApi.INSTANCE.applyChunkUpdate(snapshot,level,true);count++;it.remove();
        }
    }
}
