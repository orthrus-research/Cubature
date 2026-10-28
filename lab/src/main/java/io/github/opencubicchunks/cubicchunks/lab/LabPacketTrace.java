package io.github.opencubicchunks.cubicchunks.lab;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scalars only: never retains a packet, world or native buffer. Enabled only in diagnostic captures. */
final class LabPacketTrace {
    private static final int WIDTH=13, CAPACITY=32768;
    private final long[] rows=new long[WIDTH*CAPACITY];
    private int size;
    private long count;
    void add(int stage,int packet,long fingerprint,int cubes,long bytes,long start,long finish,long queued,
             boolean completed,long lighting,int pending,long oldest) {
        count++;
        if(size==CAPACITY)return;
        int i=size++*WIDTH;
        rows[i++]=count;rows[i++]=stage;rows[i++]=packet;rows[i++]=fingerprint;rows[i++]=cubes;rows[i++]=bytes;
        rows[i++]=start;rows[i++]=finish;rows[i++]=queued;rows[i++]=completed?1:0;rows[i++]=lighting;rows[i++]=pending;rows[i]=oldest;
    }
    Map<String,Object> summary() {
        Snapshot result=new Snapshot(Arrays.copyOf(rows,size*WIDTH));
        result.put("count",count);result.put("storedCount",size);result.put("droppedCount",count-size);
        result.put("stageCodes","1=snapshot,2=clientQueued,3=clientApply,4=ownerSendStage");
        result.put("scope","Diagnostic only. Packet identity hash and coordinate fingerprint are correlation hints, not unique cross-JVM IDs or content hashes. Timestamps use this JVM's monotonic clock; no packet/world retained. Body bytes are serialized payload before transport compression; -1/Long.MIN_VALUE represent unavailable values, never zero-byte measurements. Nested stages overlap.");
        return result;
    }
    private static final class Snapshot extends LinkedHashMap<String,Object> implements LabCsvSnapshot {
        private final long[] values;
        Snapshot(long[] values){this.values=values;}
        public void exportCsv(Path path)throws IOException {
            try(BufferedWriter writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                writer.write("eventOrdinal,stageCode,packetIdentityHash,coordinateFingerprint,cubes,bodyBytes,startedNanos,finishedNanos,queuedNanos,completed,lightingNanos,pendingCubes,oldestPendingAgeNanos\n");
                for(int i=0;i<values.length;i++){if(i%WIDTH!=0)writer.write(',');writer.write(Long.toString(values[i]));if(i%WIDTH==WIDTH-1)writer.write('\n');}
            }
            put("chronologicalCsv",path.getFileName().toString());
        }
    }
}
