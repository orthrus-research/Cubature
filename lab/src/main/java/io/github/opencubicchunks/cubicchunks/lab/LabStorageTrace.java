package io.github.opencubicchunks.cubicchunks.lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/** Optional lab-only observer. Nested timings are inclusive and must never be summed. */
public final class LabStorageTrace {
    private static volatile LabStorageTrace active;
    private static final ThreadLocal<Batch> batch = new ThreadLocal<>();
    private final long origin = System.nanoTime();
    private final String utc = Instant.now().toString();
    private final int capacity;
    private final List<long[]> rows = new ArrayList<>();
    private final Map<String, Integer> operations = new LinkedHashMap<>();
    private final Map<String, Samples> timings = new LinkedHashMap<>();
    private final Map<String, Long> failures = new LinkedHashMap<>();
    private final Map<String, Long> openByOperation = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Integer> owners = new IdentityHashMap<>();
    private final IdentityHashMap<Object, IdentityHashMap<Object, Pending>> pending = new IdentityHashMap<>();
    private long events, open, late, pendingBytesMaximum, pendingCountMaximum, oldestAgeMaximum, unknownAgeMaximum;
    private long inFlightBytes, inFlightMaximum, compressedBatchMaximum;
    private boolean sealed;
    private final boolean queueAccounting=Boolean.parseBoolean(System.getProperty("cubiclab.storageQueueAccounting","true"));

    public LabStorageTrace(int capacity) { if(capacity<1)throw new IllegalArgumentException();this.capacity=capacity; }
    public static void start() {
        if(Boolean.getBoolean("cubiclab.storageDiagnostics")){
            int capacity=Integer.getInteger("cubiclab.storageTraceCapacity",120000);
            if(capacity<1||capacity>1000000)throw new IllegalArgumentException("Storage trace capacity must be 1..1000000");
            active=new LabStorageTrace(capacity);
        }
    }
    public static Map<String,Object> finish() { LabStorageTrace trace=active;active=null;return trace==null?null:trace.summary(); }
    public static Token begin(String operation,Object owner) {
        LabStorageTrace trace=active;return trace==null?null:trace.enter(operation,owner,System.nanoTime());
    }
    synchronized Token enter(String operation,Object owner,long now) {
        if(sealed)return null;open++;
        openByOperation.put(operation,openByOperation.getOrDefault(operation,0L)+1);
        Integer op=operations.get(operation);if(op==null){op=operations.size();operations.put(operation,op);timings.put(operation,new Samples(capacity));}
        Integer id=owners.get(owner);if(id==null){id=owners.size();owners.put(owner,id);}
        return new Token(this,operation,op,id,now,Thread.currentThread().getId());
    }
    public static void end(Token token,boolean success,long bytes) {
        if(token!=null)token.trace.exit(token,System.nanoTime(),success,bytes);
    }
    synchronized void exit(Token t,long now,boolean success,long bytes) {
        if(t.finished)throw new IllegalStateException("Storage span ended twice");t.finished=true;open--;
        openByOperation.put(t.name,openByOperation.get(t.name)-1);
        if(sealed){late++;return;}events++;
        timings.get(t.name).addAt(now-t.start,now);
        if(!success)failures.put(t.name,failures.getOrDefault(t.name,0L)+1);
        if(rows.size()<capacity)rows.add(new long[]{t.op,t.owner,t.thread,t.start-origin,now-origin,bytes,success?1:0});
    }
    public static final class Token {
        final LabStorageTrace trace;final String name;final int op,owner;final long start,thread;boolean finished;
        Token(LabStorageTrace trace,String name,int op,int owner,long start,long thread){this.trace=trace;this.name=name;this.op=op;this.owner=owner;this.start=start;this.thread=thread;}
    }
    public static Batch beginBatch(io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage.NBTBatch value){
        LabStorageTrace trace=active;if(trace==null)return null;if(batch.get()!=null)throw new IllegalStateException("Nested backend batch");
        long bytes=0;if(trace.queueAccounting){for(NBTTagCompound n:value.columns.values())bytes+=encodedBytes(n);for(NBTTagCompound n:value.cubes.values())bytes+=encodedBytes(n);}
        Batch current=new Batch(trace,bytes);batch.set(current);synchronized(trace){trace.inFlightBytes+=bytes;trace.inFlightMaximum=Math.max(trace.inFlightMaximum,trace.inFlightBytes);}return current;
    }
    public static void compressed(long bytes){Batch current=batch.get();if(current!=null)current.compressed+=bytes;}
    public static void endBatch(Batch current){if(current==null)return;if(batch.get()!=current)throw new IllegalStateException("Wrong batch owner");batch.remove();synchronized(current.trace){current.trace.inFlightBytes-=current.bytes;current.trace.compressedBatchMaximum=Math.max(current.trace.compressedBatchMaximum,current.compressed);}}
    public static final class Batch {final LabStorageTrace trace;final long bytes;long compressed;Batch(LabStorageTrace trace,long bytes){this.trace=trace;this.bytes=bytes;}}
    private static final class Pending { final long published,bytes;Pending(long published,long bytes){this.published=published;this.bytes=bytes;} }
    /** The caller holds the actual writer's publication monitor. Bytes are encoded NBT bytes, not heap size. */
    public static void published(Object writer,NBTTagCompound value,Map<?,NBTTagCompound> columns,Map<?,NBTTagCompound> cubes) {
        LabStorageTrace trace=active;if(trace==null||!trace.queueAccounting)return;
        long bytes=encodedBytes(value);
        synchronized(trace){trace.pending.computeIfAbsent(writer,k->new IdentityHashMap<>()).put(value,new Pending(System.nanoTime(),bytes));}
        trace.observe(writer,columns,cubes);
    }
    public static void observeQueue(Object writer,Map<?,NBTTagCompound> columns,Map<?,NBTTagCompound> cubes) {
        LabStorageTrace trace=active;if(trace!=null&&trace.queueAccounting)trace.observe(writer,columns,cubes);
    }
    private synchronized void observe(Object writer,Map<?,NBTTagCompound> columns,Map<?,NBTTagCompound> cubes) {
        if(sealed)return;
        IdentityHashMap<Object,Pending> known=pending.computeIfAbsent(writer,k->new IdentityHashMap<>());
        Set<Object> present=Collections.newSetFromMap(new IdentityHashMap<>());present.addAll(columns.values());present.addAll(cubes.values());
        known.keySet().retainAll(present);long bytes=0,oldest=0,unknown=0,now=System.nanoTime();
        for(Object nbt:present){Pending p=known.get(nbt);if(p==null){unknown++;continue;}bytes+=p.bytes;oldest=Math.max(oldest,now-p.published);}
        pendingBytesMaximum=Math.max(pendingBytesMaximum,bytes);pendingCountMaximum=Math.max(pendingCountMaximum,columns.size()+cubes.size());
        oldestAgeMaximum=Math.max(oldestAgeMaximum,oldest);unknownAgeMaximum=Math.max(unknownAgeMaximum,unknown);
        Token t=enter("queueObservation",writer,now);if(t!=null)exit(t,now,true,bytes);
    }
    public static long encodedBytes(NBTTagCompound nbt) {
        CountingOutput out=new CountingOutput();
        try{CompressedStreamTools.write(nbt,new DataOutputStream(out));}catch(IOException impossible){throw new IllegalStateException(impossible);}
        return out.count;
    }
    private static final class CountingOutput extends OutputStream {
        long count;
        @Override public void write(int value){count++;}
        @Override public void write(byte[] bytes,int offset,int length){count+=length;}
    }
    synchronized Map<String,Object> summary() {
        sealed=true;Map<String,Object> result=new LinkedHashMap<>();result.put("enabled",true);
        result.put("timings",new LinkedHashMap<String,Object>());@SuppressWarnings("unchecked") Map<String,Object> out=(Map<String,Object>)result.get("timings");
        for(Map.Entry<String,Samples> item:timings.entrySet())out.put(item.getKey(),item.getValue().summary());
        result.put("failures",new LinkedHashMap<>(failures));result.put("openSpansAtSeal",open);result.put("events",events);
        Map<String,Long> unfinished=new LinkedHashMap<>();openByOperation.forEach((name,count)->{if(count!=0)unfinished.put(name,count);});result.put("unfinishedSpansByOperation",unfinished);
        result.put("captureBoundary","Capture begins/ends inside Forge END tick. An enclosing completeServerTick may be right-censored at seal; unfinished spans are excluded from durations and identified, never reported as completed.");
        result.put("queueByteAccountingEnabled",queueAccounting);
        result.put("pendingEncodedNbtBytesMaximum",queueAccounting?pendingBytesMaximum:null);result.put("pendingEntriesMaximum",queueAccounting?pendingCountMaximum:null);
        result.put("inFlightEncodedNbtBytesMaximum",queueAccounting?inFlightMaximum:null);result.put("inFlightEncodedNbtBytesAtSeal",queueAccounting?inFlightBytes:null);result.put("compressedBytesProducedPerBatchMaximum",compressedBatchMaximum);
        result.put("oldestObservedPendingAgeNanosMaximum",queueAccounting?oldestAgeMaximum:null);result.put("preexistingUnknownAgeEntriesMaximum",queueAccounting?unknownAgeMaximum:null);
        result.put("trace",new TraceRows(this));
        result.put("scope","Opt-in instrumented lane. Inclusive completed spans, not exclusive CPU/disk time. Queue bytes count only snapshots published during capture; in-flight NBT includes measured backend batches and can overlap pending entries. Compressed bytes are produced bytes per batch, not retained heap/capacity. Do not sum these as memory. Preexisting queue ages are unavailable. No gain acceptance from diagnostic runs.");
        return result;
    }
    private static final class TraceRows extends LinkedHashMap<String,Object> implements LabCsvSnapshot {
        final long[][] rows;
        TraceRows(LabStorageTrace t){rows=t.rows.toArray(new long[0][]);put("operations",new LinkedHashMap<>(t.operations));Map<Integer,Object> labels=new TreeMap<>();t.owners.forEach((owner,id)->{Map<String,Object> label=new LinkedHashMap<>();label.put("class",owner==null?null:owner.getClass().getName());label.put("regionPath",LabStorageFaults.regionPath(owner));labels.put(id,label);});put("owners",labels);put("totalCount",t.events);put("storedCount",rows.length);put("droppedCount",t.events-rows.length);put("originMonotonicNanos",t.origin);put("originUtc",t.utc);}
        public void exportCsv(Path path)throws IOException{
            try(BufferedWriter out=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
                out.write("operation,owner,thread,startOffsetNanos,endOffsetNanos,bytes,success\n");
                for(long[] row:rows){for(int i=0;i<row.length;i++){if(i>0)out.write(',');out.write(Long.toString(row[i]));}out.write('\n');}
            }put("chronologicalCsv",path.getFileName().toString());
        }
    }
}
