package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.lib.ExtRegion;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.region.ShadowPagingRegion;
import io.github.opencubicchunks.cubicchunks.lab.LabStorageFaults;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Real file implementations, isolated handles, one injected boundary per directory. No global cache closes. */
public final class LabStorageFaultMatrix {
    private final List<Map<String,Object>> cases=new ArrayList<>();
    public LabStorageFaultMatrix(Path root)throws IOException{
        if(!Boolean.getBoolean("cubiclab.storageDiagnostics"))throw new IllegalStateException("Fault matrix requires storage diagnostic hooks");
        for(String kind:Arrays.asList("payload-before","disk-full","payload-partial","payloadForce","headerForce","header-partial"))region(root.resolve(kind),kind);
        overflow(root.resolve("overflow-rename"));
    }
    private static byte[] value(int length,int seed){byte[] bytes=new byte[length];new Random(seed).nextBytes(bytes);return bytes;}
    private static boolean matches(Optional<ByteBuffer> actual,byte[] expected){if(!actual.isPresent())return false;ByteBuffer b=actual.get();byte[] bytes=new byte[b.remaining()];b.get(bytes);return Arrays.equals(bytes,expected);}
    private void region(Path path,String kind)throws IOException{
        Files.createDirectories(path);EntryLocation3D key=new EntryLocation3D(-17,-9,31);EntryLocation3D.Provider keys=new EntryLocation3D.Provider();
        byte[] previous=value(40,1),replacement=value(2300,2);boolean threw=false,fired;String readFailure=null;boolean old=false,newer=false,retry=false;
        ShadowPagingRegion<EntryLocation3D> region=ShadowPagingRegion.<EntryLocation3D>builder().setDirectory(path).setRegionKey(key.getRegionKey()).setKeyProvider(keys).setSectorSize(512).build();
        region.writeValue(key,ByteBuffer.wrap(previous));region.flush();
        try(LabStorageFaults.Scope scope=LabStorageFaults.arm(path,kind)){
            try{region.writeValue(key,ByteBuffer.wrap(replacement));}catch(IOException expected){threw=true;}
            fired=scope.fired;
        }
        // Close may perform maintenance; this is exception/reopen testing, not crash simulation.
        region.close();
        region=ShadowPagingRegion.<EntryLocation3D>builder().setDirectory(path).setRegionKey(key.getRegionKey()).setKeyProvider(keys).setSectorSize(512).build();
        try{old=matches(region.readValue(key),previous);newer=matches(region.readValue(key),replacement);}catch(IOException|RuntimeException e){readFailure=e.toString();}
        try{region.writeValue(key,ByteBuffer.wrap(replacement));region.flush();retry=matches(region.readValue(key),replacement);}finally{region.close();}
        Map<String,Object> out=new LinkedHashMap<>();out.put("kind",kind);out.put("injectionObserved",fired);out.put("exceptionObserved",threw);out.put("oldValueAfterReopen",old);out.put("newValueAfterReopen",newer);out.put("readFailure",readFailure);out.put("retryRecovered",retry);out.put("directory",path.toString());cases.add(out);
        if(!fired||!threw||!retry)throw new IllegalStateException("Fault harness failed its injection/retry contract: "+out);
    }
    private void overflow(Path path)throws IOException{
        Files.createDirectories(path);EntryLocation3D key=new EntryLocation3D(-17,-9,31);EntryLocation3D.Provider keys=new EntryLocation3D.Provider();
        ExtRegion<EntryLocation3D> region=new ExtRegion<>(path,Collections.emptyList(),keys,key.getRegionKey());
        byte[] previous=value(180000,3),replacement=value(190000,4);region.writeValue(key,ByteBuffer.wrap(previous));boolean threw=false,fired;
        try(LabStorageFaults.Scope scope=LabStorageFaults.arm(path,"overflow-rename")){
            try{region.writeValue(key,ByteBuffer.wrap(replacement));}catch(IOException expected){threw=true;}fired=scope.fired;
        }
        boolean old=matches(region.readValue(key),previous);region.writeValue(key,ByteBuffer.wrap(replacement));boolean retry=matches(region.readValue(key),replacement);region.close();
        Map<String,Object> out=new LinkedHashMap<>();out.put("kind","overflow-rename");out.put("injectionObserved",fired);out.put("exceptionObserved",threw);out.put("oldValueAfterFailure",old);out.put("retryRecovered",retry);out.put("directory",path.toString());cases.add(out);
        if(!fired||!threw||!old||!retry)throw new IllegalStateException("Overflow fault harness failed: "+out);
    }
    public Map<String,Object> summary(){Map<String,Object> out=new LinkedHashMap<>();out.put("cases",cases);out.put("harnessPassed",cases.size()==7);out.put("scope","Actual shadow-paging and external-file implementations in isolated directories; operation exceptions and partial writes with orderly close/reopen, not process crash or power loss. Disk-full is an injected IOException at payload write, not exhaustion of the host disk. Reopen observations describe engine behavior and are not promoted to durability guarantees.");return out;}
}
