package io.github.opencubicchunks.cubicchunks.lab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.*;

/** Faults require both a thread-owned scope and an exact registered fixture directory. */
public final class LabStorageFaults {
    private static final Map<Object,Path> regions=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Scope> active=new ThreadLocal<>();
    public static void register(Object region,Path file){regions.put(region,file.toAbsolutePath().normalize());}
    public static String regionPath(Object region){Path path=regions.get(region);return path==null?null:path.toString();}
    public static Scope arm(Path directory,String kind){
        if(active.get()!=null)throw new IllegalStateException("Nested storage fault scope");
        Scope scope=new Scope(directory.toAbsolutePath().normalize(),kind);active.set(scope);return scope;
    }
    public static void before(Object region,String kind)throws IOException{Path path=regions.get(region);if(path!=null)beforePath(path,kind);}
    public static void beforePath(Path path,String kind)throws IOException{
        if(claim(path,kind))throw new IOException("Lab injected "+kind+" at "+path);
        if(claim(path,"pause-"+kind)){
            Scope scope=active.get();Map<String,Object> ready=new LinkedHashMap<>();ready.put("boundary",kind);ready.put("path",path.toString());
            ready.put("scope","Fixture thread paused before this operation; controller may terminate only its owned server process. OS remains running.");
            LabData.write(scope.directory.resolve("boundary-ready.json"),ready);
            long deadline=System.nanoTime()+90000000000L;
            while(System.nanoTime()<deadline)java.util.concurrent.locks.LockSupport.parkNanos(100000000L);
            throw new IOException("Crash controller did not terminate the fixture within 90 seconds");
        }
    }
    static boolean claim(Path path,String kind){
        Scope scope=active.get();if(scope==null||scope.fired||!scope.kind.equals(kind)||!path.toAbsolutePath().normalize().startsWith(scope.directory))return false;
        scope.fired=true;return true;
    }
    public static void payload(Object region,GatheringByteChannel channel,ByteBuffer[] buffers)throws IOException{
        before(region,"payload-before");before(region,"disk-full");Path path=regions.get(region);
        if(path!=null&&claim(path,"payload-partial")){
            for(ByteBuffer buffer:buffers)if(buffer.hasRemaining()){ByteBuffer part=buffer.duplicate();part.limit(part.position()+1);while(part.hasRemaining())channel.write(new ByteBuffer[]{part});break;}
            throw new IOException("Lab partial payload write");
        }
    }
    public static void header(Object region,ByteChannel channel,ByteBuffer buffer)throws IOException{
        Path path=regions.get(region);
        if(path!=null&&claim(path,"header-partial")){
            ByteBuffer part=buffer.duplicate();part.limit(part.position()+Math.min(3,part.remaining()));while(part.hasRemaining())channel.write(part);
            throw new IOException("Lab partial header write");
        }
    }
    public static final class Scope implements AutoCloseable {
        final Path directory;final String kind;public boolean fired;
        Scope(Path directory,String kind){this.directory=directory;this.kind=kind;}
        public void close(){if(active.get()!=this)throw new IllegalStateException("Wrong fault owner");active.remove();}
    }
}
