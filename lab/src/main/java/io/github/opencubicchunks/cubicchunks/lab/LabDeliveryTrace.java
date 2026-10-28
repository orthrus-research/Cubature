package io.github.opencubicchunks.cubicchunks.lab;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded diagnostic events. Coordinates and packet fingerprints are correlation hints, not revision IDs. */
public final class LabDeliveryTrace {
    public static final boolean ENABLED = Boolean.getBoolean("cubiclab.deliveryTrace");
    private static volatile LabDeliveryTrace current;
    private static long epochs;
    private final long epoch = ++epochs, origin = System.nanoTime();
    private final long[][] rows = new long[8][1000000];
    private final Map<String,Integer> operations = new LinkedHashMap<>();
    private int size;
    private long dropped;
    private boolean sealed;
    private LabDeliveryTrace() {}
    public static void begin() { if (ENABLED) current = new LabDeliveryTrace(); }
    public static long epoch() { LabDeliveryTrace t=current;return t==null?0:t.epoch; }
    public static void event(String op,int x,int y,int z,long token,long detail) {
        LabDeliveryTrace t=current;if(t!=null)t.add(op,x,y,z,token,detail);
    }
    public static void packet(String op,Object packet) {
        if(current==null || !(packet instanceof LabDeliveryPacket))return;
        io.github.opencubicchunks.cubicchunks.core.network.PacketCubes p=
            (io.github.opencubicchunks.cubicchunks.core.network.PacketCubes)packet;
        long fingerprint=p.getCoordinateFingerprint();
        for(io.github.opencubicchunks.cubicchunks.api.util.CubePos pos:((LabDeliveryPacket)packet).cubiclab$positions())
            event(op,pos.getX(),pos.getY(),pos.getZ(),fingerprint,System.identityHashCode(packet));
    }
    private synchronized void add(String op,int x,int y,int z,long token,long detail) {
        if(sealed)return;
        if(size==rows[0].length){dropped++;return;}
        Integer operation=operations.get(op);
        if(operation==null){operation=operations.size();operations.put(op,operation);}
        int i=size++;rows[0][i]=System.nanoTime()-origin;rows[1][i]=Thread.currentThread().getId();
        rows[2][i]=operation;rows[3][i]=x;rows[4][i]=y;rows[5][i]=z;rows[6][i]=token;rows[7][i]=detail;
    }
    public static Map<String,Object> finish(Path directory) {
        LabDeliveryTrace t=current;current=null;return t==null?null:t.write(directory);
    }
    private synchronized Map<String,Object> write(Path directory) {
        sealed=true;
        try {
            Files.createDirectories(directory);
            try(BufferedWriter writer=Files.newBufferedWriter(directory.resolve("delivery.csv"),
                    StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                writer.write("nanos,thread,operation,x,y,z,token,detail\n");
                for(int i=0;i<size;i++){for(int j=0;j<8;j++){if(j!=0)writer.write(',');writer.write(Long.toString(rows[j][i]));}writer.write('\n');}
            }
        } catch(IOException e){throw new IllegalStateException("Cannot retain delivery trace",e);}
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("file","delivery.csv");out.put("operations",operations);out.put("stored",size);
        out.put("dropped",dropped);out.put("originMonotonicNanos",origin);out.put("epoch",epoch);
        Map<String,Integer> nativeBits=new LinkedHashMap<>();
        nativeBits.put("dummy",1);nativeBits.put("skipped",2);nativeBits.put("dirty",4);
        nativeBits.put("resident",8);nativeBits.put("empty",16);out.put("nativeOutstandingBits",nativeBits);
        Map<String,Integer> dhBits=new LinkedHashMap<>();
        dhBits.put("enabled",1);dhBits.put("canRender",2);dhBits.put("gpuUploadComplete",4);out.put("dhSelectionBits",dhBits);
        out.put("scope","Single-world dedicated lab process; monotonic clock local to JVM. Native coordinates are cubes. "
            +"DH token is packed section position; x/y/z unused. Packet token is coordinate fingerprint, not globally unique. "
            +"Renderer object/task identities are process-local; revision IDs and physical presentation are unavailable.");
        return out;
    }
}
