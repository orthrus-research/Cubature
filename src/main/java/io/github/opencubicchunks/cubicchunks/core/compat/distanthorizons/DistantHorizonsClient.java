package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Coalesces packet notifications on the client owner; conversion/persistence stay in DH. Also keeps the client's
 * arrival plan (ArrivalPlan) current for DH's request queue and quad tree.
 */
public final class DistantHorizonsClient {
    private static final ArrivalPlan PLAN=new ArrivalPlan();
    private static volatile boolean planning;
    private static final AtomicLong held=new AtomicLong(), cancelled=new AtomicLong();
    private static final Map<String,Field> FIELDS=new ConcurrentHashMap<>();
    private double nativeShare=1;
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
        if(event.getWorld()==world) {dirty.clear();world=null;planning=false;cubicRendering(false);}
    }

    /** Sorts DH's missing sections by the arrival plan, leaving out those it holds; false to keep DH's order. */
    public static boolean orderByArrival(ArrayList<Long> missing) {
        if(!planning)return false;
        int n=missing.size();long[] keys=new long[n],positions=new long[n];int kept=0;
        for(Long pos:missing) {
            long key=key(pos);
            if(key==ArrivalPlan.HOLD){held.incrementAndGet();continue;}
            keys[kept]=key;positions[kept++]=pos;
        }
        Integer[] order=new Integer[kept];
        for(int i=0;i<kept;i++)order[i]=i;
        Arrays.sort(order,(a,b)->Long.compare(keys[a],keys[b]));
        missing.clear();
        for(int i:order)missing.add(positions[i]);
        return true;
    }

    /** Whether DH's tree should end an idle queueing thread so its next update finds the sections wanted now. */
    public static boolean refreshesWanted() { return planning; }

    /** Whether a DH client request queue is a cubic world's world generation queue the plan orders. */
    public static boolean generationQueue(Object queue) {
        return planning && queue.getClass().getSimpleName().equals("RemoteWorldRetrievalQueue");
    }

    public static void requestQueued(long pos) { PLAN.queued(pos,System.nanoTime()); }

    /**
     * The waiting request to send next by the arrival plan; unsent requests it now holds are cancelled, so DH's tree
     * keeps them missing until they qualify again.
     */
    public static Optional<Object> nextRequest(Stream<Object> waiting, Comparator<Object> nearestFirst) {
        List<Object> entries=waiting.collect(Collectors.toList());
        Object best=null;long bestKey=ArrivalPlan.HOLD;List<Object> holding=new ArrayList<>();
        for(Object entry:entries) {
            long key=key((Long)((Map.Entry<?,?>)entry).getKey());
            if(key==ArrivalPlan.HOLD)holding.add(entry);
            else if(best==null || key<bestKey){best=entry;bestKey=key;}
        }
        for(Object entry:holding) {
            Object future=field(((Map.Entry<?,?>)entry).getValue(),"future");
            if(future instanceof CompletableFuture && ((CompletableFuture<?>)future).cancel(false))cancelled.incrementAndGet();
        }
        if(best!=null)PLAN.sent(ArrivalPlan.coarse(bestKey));
        return Optional.ofNullable(best);
    }

    /** How many of the generation queue's requests may be in flight, given DH's limit. */
    public static int inflight(int limit) { return PLAN.inflight(limit); }

    public static void requestAnswered(Object task) {
        long pos=(Long)field(task,"pos");
        PLAN.arrived(pos,System.nanoTime(),DhSectionPos.getDetailLevel(pos),DhSectionPos.getMinCornerBlockX(pos),
                DhSectionPos.getMinCornerBlockZ(pos),DhSectionPos.getBlockWidth(pos));
    }

    private static long key(long pos) {
        return PLAN.key(DhSectionPos.getDetailLevel(pos),DhSectionPos.getMinCornerBlockX(pos),DhSectionPos.getMinCornerBlockZ(pos),
                DhSectionPos.getBlockWidth(pos));
    }

    private static Object field(Object owner,String name) {
        Field f=FIELDS.computeIfAbsent(owner.getClass().getName()+"#"+name,k->{
            for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass())
                try{Field found=c.getDeclaredField(name);found.setAccessible(true);return found;}catch(NoSuchFieldException ignored){}
            throw new IllegalStateException("No field "+name+" on "+owner.getClass());
        });
        try{return f.get(owner);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }

    /** The plan's view of the player, the native view's fill and DH's detail rings, once a client tick. */
    private void plan(WorldClient world) {
        EntityPlayerSP player=Minecraft.getMinecraft().player;
        if(!CubicChunksConfig.distantHorizonsArrivalPlan || player==null){planning=false;return;}
        int view=Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
        if(ticks%10==0) {
            int cx=(int)Math.floor(player.posX)>>4,cz=(int)Math.floor(player.posZ)>>4,loaded=0,judged=0,shown=0;
            Set<Long> unshown=new HashSet<>();
            for(int x=cx-view;x<=cx+view;x++)for(int z=cz-view;z<=cz+view;z++) {
                Chunk column=world.getChunkProvider().getLoadedChunk(x,z);
                if(column==null)continue;
                loaded++;
                // A column whose surface lies outside its received cubes shows no ground: let DH's LOD stand there.
                Boolean surface=CubicChunkSnapshot.showsSurface(column);
                if(surface==null)continue;
                judged++;
                if(surface)shown++;else unshown.add(ArrivalPlan.chunkKey(x,z));
            }
            nativeShare=loaded/(double)((2*view+1)*(2*view+1));
            PLAN.nativeGround(unshown,judged==0?1:shown/(double)judged);
            EDhApiHorizontalQuality quality=Config.Client.Advanced.Graphics.Quality.horizontalQuality.get();
            PLAN.rings(quality.distanceUnitInBlocks*16.0,quality.quadraticBase);
        }
        PLAN.observe(System.nanoTime(),player.posX,player.posZ,view*16+8,nativeShare);
        planning=true;
        if(ticks%200==0)
            CubicChunks.LOGGER.info("CC/DH arrival plan: on-time detail {}-block, speed {} b/s, native view {}% loaded ({}% show their ground), in flight {} ({} answers/s), held {}, cancelled {}, service s 1/2/4/8/16-block {}/{}/{}/{}/{}",
                1<<(PLAN.floor()-ArrivalPlan.FINEST),Math.round(PLAN.speed()),Math.round(nativeShare*100),Math.round(PLAN.groundShare()*100),PLAN.inflight(40),
                String.format("%.1f",PLAN.answerRate()),held.get(),cancelled.get(),
                Math.round(PLAN.serviceSeconds(6)),Math.round(PLAN.serviceSeconds(7)),Math.round(PLAN.serviceSeconds(8)),
                Math.round(PLAN.serviceSeconds(9)),Math.round(PLAN.serviceSeconds(10)));
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
        plan(world);
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
