package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.api.region.IRegion;
import cubicchunks.regionlib.lib.ExtRegion;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.ShadowPagingRegion;
import io.github.opencubicchunks.cubicchunks.lab.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** The controller kills an owned server paused at a real storage boundary in isolated files. */
public final class LabStorageCrash {
    private static final EntryLocation3D KEY=new EntryLocation3D(-17,-9,31);
    private static byte[] bytes(int size,int seed){byte[] b=new byte[size];new Random(seed).nextBytes(b);return b;}
    private static boolean matches(Optional<ByteBuffer> value,byte[] expected){if(!value.isPresent())return false;ByteBuffer b=value.get();byte[] actual=new byte[b.remaining()];b.get(actual);return Arrays.equals(actual,expected);}
    public static void run(MinecraftServer server,String id,String boundary,boolean recover)throws IOException{
        if(!Boolean.getBoolean("cubiclab.storageDiagnostics")||!id.matches("[a-z0-9-]{1,48}")||!Arrays.asList("payloadForce","headerForce","overflow-rename","after-barrier").contains(boundary))throw new IllegalArgumentException("Invalid crash fixture input");
        Path root=server.getFile("cubiclab-crashes").toPath().resolve(id);
        if(!recover){Files.createDirectories(root.getParent());Files.createDirectory(root);
            if(server.getCommandManager().executeCommand(server,"save-all flush")!=1)throw new IOException("Preflight world save failed");
            Map<String,Object> input=new LinkedHashMap<>();input.put("boundary",boundary);input.put("runtime",LabData.runtime());LabData.write(root.resolve("input.json"),input);
        }else if(!Files.isRegularFile(root.resolve("boundary-ready.json")))throw new IOException("No observed crash boundary");
        boolean overflow=boundary.equals("overflow-rename");byte[] previous=bytes(overflow?180000:40,11),replacement=bytes(overflow?190000:2300,12);
        if(!recover){Files.write(root.resolve("previous.bin"),previous,StandardOpenOption.CREATE_NEW);Files.write(root.resolve("replacement.bin"),replacement,StandardOpenOption.CREATE_NEW);}
        IRegion<EntryLocation3D> region=overflow?new ExtRegion<>(root,Collections.emptyList(),new EntryLocation3D.Provider(),KEY.getRegionKey()):
            ShadowPagingRegion.<EntryLocation3D>builder().setDirectory(root).setRegionKey(KEY.getRegionKey()).setKeyProvider(new EntryLocation3D.Provider()).setSectorSize(512).build();
        if(recover){
            boolean old=false,newer=false;String error=null;
            try{old=matches(region.readValue(KEY),previous);newer=matches(region.readValue(KEY),replacement);}catch(IOException|RuntimeException e){error=e.toString();}
            Map<String,Object> out=new LinkedHashMap<>();out.put("boundary",boundary);out.put("oldValue",old);out.put("newValue",newer);out.put("readFailure",error);
            region.writeValue(KEY,ByteBuffer.wrap(replacement));region.flush();boolean retry=matches(region.readValue(KEY),replacement);region.close();out.put("retryRecovered",retry);
            boolean expected=boundary.equals("after-barrier")?newer:boundary.equals("headerForce")?old||newer:old;
            out.put("passed",expected&&retry);out.put("scope","New native JVM after owned-process termination; OS/page cache remain alive. This cannot prove power-loss durability.");LabData.write(root.resolve("recovery.json"),out);
            if(!expected||!retry)throw new IOException("Crash recovery contract failed: "+out);
            server.sendMessage(new TextComponentString("CUBICLAB_CRASH_RECOVERED id="+id+" PASS"));return;
        }
        region.writeValue(KEY,ByteBuffer.wrap(previous));region.flush();
        try(LabStorageFaults.Scope scope=LabStorageFaults.arm(root,"pause-"+boundary)){
            region.writeValue(KEY,ByteBuffer.wrap(replacement));region.flush();
            if(boundary.equals("after-barrier"))LabStorageFaults.beforePath(root.resolve("completed"),"after-barrier");
            throw new IOException("Requested crash boundary was not reached");
        }finally{region.close();}
    }
}
