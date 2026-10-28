package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.core.lighting.LightingRules;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import javax.annotation.Nullable;

/**
 * Immutable owner-thread copy of a contiguous, received vertical slice. DH workers
 * never read live cubes, force-load a missing cube, or substitute missing cubes for air.
 * World minY is the encoding datum; received minY only bounds the scanned data.
 */
public final class CubicChunkSnapshot extends ChunkWrapper {
    private final Data data;
    private final ILevelWrapper level;

    private static final class Section {
        IBlockState[] states;
        byte[] blockLight, skyLight;
    }
    private static final class Data {
        int minY, maxY, firstCube, lowestReceivedY, highestReceivedY, hash;
        int[] opacityTop;
        Biome[] biomes;
        Section[] sections;
        boolean hasSky;
        volatile int[] solidTops;
        int[] blockingTops;
        IBlockState[] foundation;
    }

    private CubicChunkSnapshot(Chunk column, ILevelWrapper level, Data data) {
        super(column, level); this.level=level; this.data=data;
        setIsDhBlockLightCorrect(true); setIsDhSkyLightCorrect(true);
    }

    @Nullable public static CubicChunkSnapshot capture(Chunk column, ILevelWrapper level) {
        ICubicWorld world=(ICubicWorld)column.getWorld();
        IColumn cubes=(IColumn)column;
        Data d=new Data(); d.minY=world.getMinHeight(); d.maxY=world.getMaxHeight();
        // DH encodes relative heights in twelve bits; unbounded CC worlds need another policy.
        if ((long)d.maxY-d.minY<=0 || (long)d.maxY-d.minY>4095) return null;
        int first=Integer.MAX_VALUE,last=Integer.MIN_VALUE,highestOpaque=Integer.MIN_VALUE,lowestOpaque=Integer.MAX_VALUE;
        for (ICube cube:cubes.getLoadedCubes()) {
            if (cube.getY()*16L>=d.minY && cube.getY()*16L<d.maxY) {
                first=Math.min(first,cube.getY()); last=Math.max(last,cube.getY());
            }
        }
        if (first>last) return null;
        for(int cy=first;cy<=last;cy++)if(cubes.getLoadedCube(cy)==null)return null;
        d.opacityTop=new int[256]; d.biomes=new Biome[256];
        byte[] biomeIds=column.getBiomeArray();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            int index=z*16+x, top=cubes.getOpacityIndex().getTopBlockY(x,z);
            d.opacityTop[index]=top; highestOpaque=Math.max(highestOpaque,top);lowestOpaque=Math.min(lowestOpaque,top);
            Biome biome=Biome.getBiome(biomeIds[index]&255);
            if(biome==null) return null; // wait for authoritative column registration/data
            d.biomes[index]=biome;
        }
        // Require the known surface plus air above it. An underground-only slice is not a surface LOD.
        if(highestOpaque>=d.maxY || lowestOpaque<(long)first*16 || highestOpaque+1L>(long)(last+1)*16-1) return null;
        d.sections=new Section[last-first+1]; d.firstCube=first;
        d.lowestReceivedY=Math.max(d.minY,first*16); d.highestReceivedY=Math.min(d.maxY,(last+1)*16);
        d.hasSky=column.getWorld().provider.hasSkyLight();
        int hash=31*(31*d.minY+d.maxY)+d.lowestReceivedY;
        for(int cy=first;cy<=last;cy++) {
            ICube cube=cubes.getLoadedCube(cy);
            if(cube==null) return null; // a packet gap must not become an empty section
            Section section=new Section();d.sections[cy-first]=section;
            ExtendedBlockStorage storage=cube.getStorage();
            if(storage!=null) {
                section.blockLight=storage.getBlockLight().getData().clone();
                if(d.hasSky && storage.getSkyLight()!=null) section.skyLight=storage.getSkyLight().getData().clone();
                if(!storage.isEmpty()) {
                    section.states=new IBlockState[4096];
                    for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        IBlockState state=storage.get(x,y,z);
                        section.states[(y*16+z)*16+x]=state;
                        hash=31*hash+Block.BLOCK_STATE_IDS.get(state);
                    }
                }
                hash=31*hash+Arrays.hashCode(section.blockLight);
                hash=31*hash+Arrays.hashCode(section.skyLight);
            }
        }
        // DH finds each column's top by reading upward from its height map while blocks are not
        // air, through water and leaves as well as opaque blocks. The top layer of the slice must
        // be air everywhere, or that walk leaves the slice; wait for the cubes above instead.
        IBlockState[] top=d.sections[d.sections.length-1].states;
        if(top!=null && d.highestReceivedY<d.maxY)
            for(int i=15*256;i<4096;i++) if(top[i].getMaterial()!=Material.AIR) return null;
        // Below the received slice the column is solid ground DH cannot see, not air: report each
        // column's lowest received opaque block (stone where it is open) down to the world floor, so
        // a visited column never becomes a floating shell that replaces its full preview column.
        d.foundation=new IBlockState[256];
        IBlockState[] bottom=d.sections[0].states;
        for(int i=0;i<256;i++) {
            IBlockState state=bottom==null?null:bottom[i];
            d.foundation[i]=state!=null && state.isOpaqueCube()?state:Blocks.STONE.getDefaultState();
            hash=31*hash+Block.BLOCK_STATE_IDS.get(d.foundation[i]);
        }
        d.hash=31*(31*hash+Arrays.hashCode(biomeIds))+Arrays.hashCode(d.opacityTop);
        return new CubicChunkSnapshot(column,level,d);
    }

    @Override public CubicChunkSnapshot copy() { return new CubicChunkSnapshot(getChunk(),level,data); }
    @Override public CubicChunkSnapshot copyWithLevel(ILevelWrapper other) { return new CubicChunkSnapshot(getChunk(),other,data); }
    @Override public int getHeight() { return data.maxY-data.minY; }
    @Override public int getInclusiveMinBuildHeight() { return data.minY; }
    @Override public int getExclusiveMaxBuildHeight() { return data.maxY; }
    @Override public int getMinNonEmptyHeight() { return data.minY; }
    @Override public int getMaxNonEmptyHeight() { return data.highestReceivedY-1; }
    @Override public int getBlockBiomeHashCode() { return data.hash; }
    @Override public int roughHashCode() { return data.hash; }
    @Override public void createDhHeightMaps() {
        if(data.solidTops!=null)return;
        synchronized(data) {
            if(data.solidTops!=null)return;
            // Let DH derive both maps from our immutable block reads, once per payload.
            super.createDhHeightMaps();
            int[] solid=new int[256],blocking=new int[256];
            // DH starts its top search at these values. A column with nothing DH counts as solid
            // inside the received slice derives a height below it, so clamp both maps into it.
            int low=data.minY,high=data.highestReceivedY-1;
            for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                solid[z*16+x]=Math.max(low,Math.min(high,super.getSolidHeightMapValue(x,z)));
                blocking[z*16+x]=Math.max(low,Math.min(high,super.getLightBlockingHeightMapValue(x,z)));
            }
            data.blockingTops=blocking;data.solidTops=solid;
        }
    }
    @Override public int getSolidHeightMapValue(int x,int z) { createDhHeightMaps();return data.solidTops[z*16+x]; }
    @Override public int getLightBlockingHeightMapValue(int x,int z) { createDhHeightMaps();return data.blockingTops[z*16+x]; }

    @Nullable private Section section(int y) {
        int i=(y>>4)-data.firstCube;
        return i<0||i>=data.sections.length ? null : data.sections[i];
    }
    @Override public IBlockStateWrapper getBlockState(int x,int y,int z) { return getBlockState(x,y,z,null,null); }
    @Override public IBlockStateWrapper getBlockState(int x,int y,int z,IMutableBlockPosWrapper ignored,IBlockStateWrapper guess) {
        if(x<0||x>=16||z<0||z>=16)throw new IndexOutOfBoundsException("Not a relative column position");
        if(y<data.minY||y>=data.maxY) return BlockStateWrapper.AIR;
        if(y<data.lowestReceivedY) return BlockStateWrapper.fromBlockState(data.foundation[z*16+x],level,guess);
        Section s=section(y);
        if(s==null)throw new IllegalStateException("DH requested an unreceived cube at Y="+y);
        IBlockState state=s.states==null?Blocks.AIR.getDefaultState():s.states[((y&15)*16+z)*16+x];
        return BlockStateWrapper.fromBlockState(state,level,guess);
    }
    @Override public IBiomeWrapper getBiome(int x,int y,int z) { return BiomeWrapper.getBiomeWrapper(data.biomes[z*16+x],level); }
    @Override public int getDhBlockLight(int x,int y,int z) {
        Section s=section(y);return s==null||s.blockLight==null?0:nibble(s.blockLight,x,y,z);
    }
    @Override public int getDhSkyLight(int x,int y,int z) {
        Section s=section(y);
        return s!=null&&s.skyLight!=null?nibble(s.skyLight,x,y,z):LightingRules.emptySkyLight(data.hasSky,data.opacityTop[z*16+x],y);
    }
    private static int nibble(byte[] values,int x,int y,int z) {
        int i=((y&15)*16+z)*16+x;return (values[i>>1]>>>((i&1)*4))&15;
    }
    @Override public ArrayList<DhBlockPos> getWorldBlockLightPosList() {
        ArrayList<DhBlockPos> emitters=new ArrayList<>();
        for(int cy=0;cy<data.sections.length;cy++) {
            IBlockState[] states=data.sections[cy].states;
            if(states==null)continue;
            for(int i=0;i<states.length;i++)if(states[i].getLightValue()>0)
                emitters.add(new DhBlockPos(getMinBlockX()+(i&15),(data.firstCube+cy)*16+(i>>8),getMinBlockZ()+((i>>4)&15)));
        }
        return emitters;
    }
    @Override public void setDhBlockLight(int x,int y,int z,int value) { throw new UnsupportedOperationException("Native CC light snapshot"); }
    @Override public void setDhSkyLight(int x,int y,int z,int value) { throw new UnsupportedOperationException("Native CC light snapshot"); }
    @Override public void clearDhBlockLighting() { throw new UnsupportedOperationException("Native CC light snapshot"); }
    @Override public void clearDhSkyLighting() { throw new UnsupportedOperationException("Native CC light snapshot"); }
}
