package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.server.CubeDemand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.BlockLiquid;
import net.minecraft.init.Blocks;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;

/**
 * DH owns admission, worker threads, pooled data, persistence and network delivery, except that no tile starts while
 * the world's native view is behind (PreviewAdmission).
 */
final class CubicTerrainPreviewGenerator implements IDhApiWorldGenerator, PreviewAdmission {
    private final ICubicTerrainPreview provider;
    private final ILevelWrapper level;
    private final int minY,maxY;
    private final AtomicLong tiles=new AtomicLong(),samples=new AtomicLong(),nanos=new AtomicLong();
    private final AtomicLongArray detailTiles=new AtomicLongArray(13);
    private volatile boolean closed;

    CubicTerrainPreviewGenerator(ICubicTerrainPreview provider,ILevelWrapper level) {
        this.provider=provider;this.level=level;minY=level.getMinHeight();maxY=level.getMaxHeight();
        if ((long)maxY-minY<=0 || (long)maxY-minY>4095)
            throw new IllegalArgumentException("DH requires a bounded cubic allocation of at most 4095 blocks");
    }

    @Override public byte getSmallestDataDetailLevel() { return 0; }
    @Override public byte getLargestDataDetailLevel() { return 12; }
    @Override public EDhApiWorldGeneratorReturnType getReturnType() { return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES; }
    @Override public void preGeneratorTaskStart() {}
    @Override public void close() { closed=true; }

    /** Native view first: the native view of this generator's world is behind (CubeDemand.nativeBehind). */
    @Override public boolean holdsNewTiles() {
        Object world=level.getWrappedMcObject();
        if (!(world instanceof WorldServer)) return false;
        PlayerChunkMap map=((WorldServer)world).getPlayerChunkMap();
        return map instanceof CubeDemand && ((CubeDemand)map).nativeBehind();
    }

    @Override public CompletableFuture<Void> generateLod(int minChunkX,int minChunkZ,int lodX,int lodZ,byte detail,
            IDhApiFullDataSource data,EDhApiDistantGeneratorMode mode,ExecutorService executor,
            Consumer<IDhApiFullDataSource> consumer) {
        return CompletableFuture.runAsync(() -> {
            if (provider==null) throw new UnsupportedOperationException("Cubic generator has no ICubicTerrainPreview; vanilla DH generation is unsafe for cubes");
            if (detail<0 || detail>12) throw new IllegalArgumentException("Unsupported DH detail "+detail);
            long started=System.nanoTime();
            int step=1<<detail,width=data.getWidthInDataColumns();
            long baseX=(long)minChunkX*16,baseZ=(long)minChunkZ*16;
            ICubicTerrainPreview.Sampler sampler=provider.createTerrainPreviewSampler();
            Column writer=new Column();
            for (int z=0;z<width;z++) {
                if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("Cubic preview closed");
                for (int x=0;x<width;x++) {
                    // One native column at each coarse cell's integer centre; the sampler is told
                    // the cell width so it may show sub-cell features by their cover.
                    int wx=Math.toIntExact(baseX+(long)x*step+step/2);
                    int wz=Math.toIntExact(baseZ+(long)z*step+step/2);
                    sampler.sampleColumn(wx,wz,step,writer);
                    writer.finish(data,x,z);
                }
            }
            if (closed) throw new CancellationException("Cubic preview closed");
            consumer.accept(data);
            samples.addAndGet((long)width*width);nanos.addAndGet(System.nanoTime()-started);
            long done=tiles.incrementAndGet();
            if (detailTiles.incrementAndGet(detail)==1 || done%64==0) CubicChunks.LOGGER.info(
                    "CC/DH distant: tiles={} sampledColumns={} detail={} lastTile=[{},{}] step={} generationMs={}",
                    done,samples.get(),detail,baseX,baseZ,step,nanos.get()/1_000_000.0);
        },executor);
    }

    private final class Column implements ICubicTerrainPreview.ColumnConsumer {
        int[] bottoms=new int[8],tops=new int[8];
        IBlockState[] states=new IBlockState[8];
        final ArrayList<DhApiTerrainDataPoint> points=new ArrayList<>(8);
        IBiomeWrapper biome;
        int count,cursor;
        boolean begun;
        @Override public void begin(Biome value) {
            if (begun) throw new IllegalArgumentException("Unfinished preview column");
            if (value==null) throw new IllegalArgumentException("Missing preview biome");
            biome=BiomeWrapper.getBiomeWrapper(value,level);count=0;cursor=minY;begun=true;
        }
        @Override public void addRun(int bottom,int top,IBlockState state) {
            if (!begun || state==null || bottom!=cursor || top<=bottom || top>maxY)
                throw new IllegalArgumentException("Preview runs must cover the allocation contiguously");
            if ((state.getMaterial().isLiquid() && !sourceWater(state)) || state.getLightValue()!=0)
                throw new IllegalArgumentException("Preview lighting does not support fluid or emitting state "+state);
            if (count==states.length) {
                states=Arrays.copyOf(states,count*2);bottoms=Arrays.copyOf(bottoms,count*2);tops=Arrays.copyOf(tops,count*2);
            }
            bottoms[count]=bottom;tops[count]=top;states[count]=state;count++;cursor=top;
        }
        void finish(IDhApiFullDataSource data,int x,int z) {
            if (!begun || cursor!=maxY) throw new IllegalArgumentException("Incomplete preview allocation");
            int sky=level.hasSkyLight()?15:0;points.clear();boolean seenWater=false;
            for (int i=count-1;i>=0;i--) {
                // The pooled source carries the horizontal scale. DH's setter requires DTO detail 0.
                // A DH run carries light above its top face, before this run blocks the sky.
                IBlockState state=states[i];
                if(sourceWater(state)) {
                    seenWater=true;
                    // The surface-only profile has no underwater air or emitters.
                    // Split only the lit water layers; the dark remainder is one run.
                    int top=tops[i];
                    while(top>bottoms[i]&&sky>0) {
                        addPoint(top-1,top,state,sky);top--;
                        sky=Math.max(0,sky-state.getLightOpacity());
                    }
                    if(top>bottoms[i])addPoint(bottoms[i],top,state,0);
                } else {
                    if(seenWater&&state.getLightOpacity()==0)
                        throw new IllegalArgumentException("Surface water preview cannot represent underwater air");
                    addPoint(bottoms[i],tops[i],state,sky);
                    // Leaves and other filtering blocks dim the sky by their opacity per block.
                    int opacity=state.getLightOpacity();
                    if(opacity>=15)sky=0;
                    else if(opacity>0)sky=(int)Math.max(0,sky-(long)opacity*(tops[i]-bottoms[i]));
                }
                states[i]=null;
            }
            data.setApiDataPointColumn(x,z,EDhApiWorldGenerationStep.SURFACE,points);
            begun=false;
        }
        private void addPoint(int bottom,int top,IBlockState state,int sky) {
            points.add(DhApiTerrainDataPoint.create((byte)0,0,sky,bottom-minY,top-minY,
                    BlockStateWrapper.fromBlockState(state,level,null),biome));
        }
    }
    private static boolean sourceWater(IBlockState state) {
        return state.getBlock()==Blocks.WATER&&state.getValue(BlockLiquid.LEVEL)==0&&state.getLightOpacity()==3;
    }
}
