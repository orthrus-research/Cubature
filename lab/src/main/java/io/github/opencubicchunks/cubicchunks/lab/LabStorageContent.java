package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.CubeDataEvent;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit diagnostic-only persistence fixture. Read mode never creates missing content. */
public final class LabStorageContent {
    private static final int[] LEVELS={-7,8,22};
    private final LabBenchmarkWorkload benchmark=Boolean.getBoolean("cubiclab.storageDiagnostics")?LabBenchmarkWorkload.configured():null;
    @CapabilityInject(State.class) public static Capability<State> CAP;
    public static final class State { int revision,eventReadRevision;byte[] payload=new byte[0]; }
    public static void initialize(){
        CapabilityManager.INSTANCE.register(State.class,new Capability.IStorage<State>(){
            public NBTBase writeNBT(Capability<State> cap,State value,EnumFacing side){NBTTagCompound n=new NBTTagCompound();n.setInteger("revision",value.revision);n.setByteArray("payload",value.payload);return n;}
            public void readNBT(Capability<State> cap,State value,EnumFacing side,NBTBase nbt){NBTTagCompound n=(NBTTagCompound)nbt;value.revision=n.getInteger("revision");value.payload=n.getByteArray("payload");}
        },State::new);
    }
    private static final class Provider implements ICapabilitySerializable<NBTTagCompound>{
        final State value=new State();
        public boolean hasCapability(Capability<?> cap,EnumFacing side){return cap==CAP;}
        public <T>T getCapability(Capability<T> cap,EnumFacing side){return cap==CAP?CAP.cast(value):null;}
        public NBTTagCompound serializeNBT(){return (NBTTagCompound)CAP.getStorage().writeNBT(CAP,value,null);}
        public void deserializeNBT(NBTTagCompound nbt){CAP.getStorage().readNBT(CAP,value,null,nbt);}
    }
    @SubscribeEvent public void attachCube(AttachCapabilitiesEvent<ICube> event){attach(event);}
    @SubscribeEvent public void attachColumn(AttachCapabilitiesEvent<Chunk> event){attach(event);}
    @SubscribeEvent public void saveEvent(CubeDataEvent.Save event){
        if(benchmark==null||event.getWorld().isRemote)return;
        State state=event.getCube().getCapability(CAP,null);
        if(state!=null&&state.revision==37)event.getData().getCompoundTag("Level").setInteger("cubiclabSaveEventRevision",37);
    }
    @SubscribeEvent public void loadEvent(CubeDataEvent.Load event){
        if(benchmark==null||event.getWorld().isRemote)return;
        State state=event.getCube().getCapability(CAP,null);
        if(state!=null)state.eventReadRevision=event.getData().getCompoundTag("Level").getInteger("cubiclabSaveEventRevision");
    }
    private void attach(AttachCapabilitiesEvent<?> event){
        if(benchmark==null)return;
        Object object=event.getObject();LabBenchmarkWorkload b=benchmark;boolean selected=false;
        if(object instanceof ICube){ICube c=(ICube)object;selected=c.getX()==b.originX&&c.getZ()==b.originZ&&Arrays.stream(LEVELS).anyMatch(y->y==c.getY());}
        if(object instanceof Chunk){Chunk c=(Chunk)object;selected=c.x==b.originX&&c.z==b.originZ;}
        if(selected)event.addCapability(new ResourceLocation("cubiclab","storage_contract"),new Provider());
    }
    private static byte[] payload(int y){byte[] data=new byte[y==8?180000:4096];new Random(71423+y).nextBytes(data);return data;}
    private static UUID identity(CubePos p){return UUID.nameUUIDFromBytes(("cubiclab-storage:"+p.getX()+":"+p.getY()+":"+p.getZ()).getBytes(StandardCharsets.UTF_8));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("Storage content: "+message);}
    static Map<String,Object> run(LabJob job,boolean write)throws java.io.IOException{
        require(Boolean.getBoolean("cubiclab.storageDiagnostics"),"requires diagnostic preparation");
        List<Map<String,Object>> witnesses=new ArrayList<>();
        for(int y:LEVELS){
            CubePos pos=new CubePos(job.benchmark.originX,y,job.benchmark.originZ);
            Cube cube=job.provider.getCubeNow(pos.getX(),y,pos.getZ(),Requirement.LOAD);
            require(cube!=null,"missing persisted cube "+pos);LabJob.retain(cube);
            BlockPos chest=new BlockPos(pos.getMinBlockX()+5,pos.getMinBlockY()+5,pos.getMinBlockZ()+5);
            BlockPos delayed=chest.east(2);
            State state=cube.getCapability(CAP,null);require(state!=null,"cube capability not attached");
            State column=cube.getColumn().getCapability(CAP,null);require(column!=null,"column capability not attached");
            UUID uuid=identity(pos);String name="storage-contract-"+y;
            if(write){
                require(cube.getEntityContainer().getEntities().stream().noneMatch(e->e.getUniqueID().equals(uuid)),"duplicate write fixture");
                job.world.setBlockState(chest,Blocks.CHEST.getDefaultState(),2);
                TileEntityChest te=(TileEntityChest)job.world.getTileEntity(chest);require(te!=null,"chest absent");
                te.setCustomName(name);te.setInventorySlotContents(0,new ItemStack(Items.DIAMOND,17));te.markDirty();
                EntityArmorStand stand=new EntityArmorStand(job.world,chest.getX()+4.5,chest.getY()+2,chest.getZ()+.5);
                stand.setUniqueId(uuid);stand.setNoGravity(true);stand.setEntityInvulnerable(true);stand.setCustomNameTag(name);
                require(job.world.spawnEntity(stand),"entity insertion rejected");
                job.world.setBlockState(delayed,Blocks.STONE.getDefaultState(),2);
                job.world.updateBlockTick(delayed,Blocks.STONE,1000000,3);
                state.revision=37;state.payload=payload(y);column.revision=91;column.payload=payload(-99);
                cube.markDirty();cube.getColumn().markDirty();
            }
            require(state.revision==37&&Arrays.equals(state.payload,payload(y)),"cube capability mismatch "+pos);
            if(!write)require(state.eventReadRevision==37,"save-event NBT/load-event witness missing");
            require(column.revision==91&&Arrays.equals(column.payload,payload(-99)),"column capability mismatch");
            require(job.world.getTileEntity(chest) instanceof TileEntityChest,"missing reloaded chest");
            TileEntityChest te=(TileEntityChest)job.world.getTileEntity(chest);
            require(name.equals(te.getName())&&te.getStackInSlot(0).getItem()==Items.DIAMOND&&te.getStackInSlot(0).getCount()==17,"chest payload mismatch");
            int entities=0;for(Entity e:cube.getEntityContainer().getEntities())if(e.getUniqueID().equals(uuid)){
                require(e instanceof EntityArmorStand&&name.equals(e.getCustomNameTag())&&e.hasNoGravity(),"entity payload mismatch");entities++;}
            require(entities==1,"entity missing or duplicated");
            int ticks=0;long remaining=0;
            for(NextTickListEntry e:((ICubicWorldInternal.Server)job.world).getScheduledTicks().getForCube(pos))if(e.position.equals(delayed)&&e.getBlock()==Blocks.STONE){
                remaining=e.scheduledTime-job.world.getTotalWorldTime();require(e.priority==3&&remaining>900000&&remaining<=1000000,"delayed tick priority/time mismatch");ticks++;}
            require(ticks==1,"delayed tick missing or duplicated");
            Map<String,Object> row=new LinkedHashMap<>();row.put("cube",Arrays.asList(pos.getX(),y,pos.getZ()));row.put("entityUuid",uuid.toString());row.put("name",name);
            row.put("chest",Arrays.asList(chest.getX(),chest.getY(),chest.getZ()));row.put("delayedTick",Arrays.asList(delayed.getX(),delayed.getY(),delayed.getZ()));row.put("remainingTicks",remaining);
            row.put("capabilityPayloadSha256",sha(state.payload));row.put("capabilityBytes",state.payload.length);row.put("columnPayloadSha256",sha(column.payload));row.put("saveEventRevision",37);witnesses.add(row);
        }
        if(write){int result=job.server.getCommandManager().executeCommand(job.server,"save-all flush");require(result==1,"complete save command failed");}
        Map<String,Object> out=new LinkedHashMap<>();out.put("mode",write?"write":"reload");out.put("passed",true);out.put("witnesses",witnesses);
        out.put("scope","Native entities, chest inventory, delayed tick priority/bounded remaining time and exact cube/column capability payloads. One incompressible capability exercises external storage. No power-loss claim.");
        job.operationCount=LEVELS.length;return out;
    }
    private static String sha(byte[] data){try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format("%02x",b&255));return s.toString();}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
