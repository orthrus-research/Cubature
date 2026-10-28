package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Coalesces packet notifications on the client owner; conversion/persistence stay in DH. */
public final class DistantHorizonsClient {
    private final Map<Chunk,Long> dirty=new LinkedHashMap<>();
    private WorldClient world;
    private long ticks, captures, notifications, captureNanos;
    private int lastLogged=-1;
    public static void init() {
        DistantHorizonsClient bridge=new DistantHorizonsClient();
        DistantHorizonsCompat.install(bridge::changed);
        MinecraftForge.EVENT_BUS.register(bridge);
        CubicChunks.LOGGER.info("Distant Horizons cubic snapshot integration enabled (visited surface columns)");
    }
    private void changed(Chunk column) {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            Minecraft.getMinecraft().addScheduledTask(() -> changed(column));return;
        }
        WorldClient current=Minecraft.getMinecraft().world;
        if(column.getWorld()!=current || !((ICubicWorld)current).isCubicWorld())return;
        if(world!=current) { dirty.clear();world=current;ticks=0;captures=0;notifications=0;captureNanos=0;lastLogged=-1;cubicRendering(true); }
        notifications++;dirty.put(column,ticks);
    }
    @SubscribeEvent public void unloaded(WorldEvent.Unload event) {
        DistantHorizonsCompat.setRemotePreview(event.getWorld(),false);
        if(event.getWorld()==world) {dirty.clear();world=null;cubicRendering(false);}
    }

    /**
     * DH fades vanilla terrain toward its LOD colour near the overdraw edge. A cubic world's vanilla
     * volume is bounded vertically, so where vanilla terrain lies inside DH's near clip there is no
     * LOD behind it and the fade blends terrain into clipped LOD walls and empty sky (see-through
     * rings, doubled terrain). Turn the fade off through DH's non-persistent API override while a
     * cubic world is loaded; the user's own setting returns on leaving it.
     */
    private static void cubicRendering(boolean cubic) {
        com.seibel.distanthorizons.core.config.types.ConfigEntry<com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode> fade=
                com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality.vanillaFadeMode;
        fade.setApiValue(cubic?com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE:null,"Cubature");
        CubicChunks.LOGGER.info("CC/DH vanilla fade: {} (cubic world {})",fade.get(),cubic);
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || world==null || world!=Minecraft.getMinecraft().world)return;
        ticks++;
        ILevelWrapper level=ProxyUtil.getLevelWrapper(world);
        if(level==null || level.getDhLevel()==null)return;
        long begin=System.nanoTime();int count=0;
        Iterator<Map.Entry<Chunk,Long>> it=dirty.entrySet().iterator();
        while(it.hasNext() && count<2 && System.nanoTime()-begin<2_000_000L) {
            Map.Entry<Chunk,Long> entry=it.next();Chunk column=entry.getKey();
            if(world.getChunkProvider().getLoadedChunk(column.x,column.z)!=column){it.remove();continue;}
            if(ticks-entry.getValue()<3 || SharedApi.isChunkAtChunkPosAlreadyUpdating(level,column.x,column.z))continue;
            long start=System.nanoTime();
            CubicChunkSnapshot snapshot=CubicChunkSnapshot.capture(column,level);
            captureNanos+=System.nanoTime()-start;
            if(snapshot==null)continue;
            SharedApi.INSTANCE.applyChunkUpdate(snapshot,level,true);
            captures++;count++;it.remove();
        }
        if(ticks%100==0 && lastLogged!=dirty.size()) {
            lastLogged=dirty.size();
            CubicChunks.LOGGER.info("CC/DH capture: ticks={} notifications={} snapshots={} pending={} copyMs={} clientH={} clientV={} height=[{},{}).",
                ticks,notifications,captures,dirty.size(),captureNanos/1_000_000.0,Minecraft.getMinecraft().gameSettings.renderDistanceChunks,
                CubicChunksConfig.verticalCubeLoadDistance,level.getMinHeight(),level.getMaxHeight());
        }
    }
}
