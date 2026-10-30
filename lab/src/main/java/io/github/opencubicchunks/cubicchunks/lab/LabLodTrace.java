package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams DH LOD stage events to {@code lod-<side>.csv} from JVM start until exit, so a directed
 * route and a free flight that ends when the player closes the client record the same way.
 * Producers never block: a full queue counts drops. The writer flushes every second and rewrites
 * {@code lod-<side>.json} each time, so a killed process keeps everything but its last second.
 * Positions are DH's packed section positions; times are this JVM's monotonic clock.
 */
public final class LabLodTrace {
    public static final String PROPERTY = "cubiclab.lodTrace";
    public static final boolean ENABLED = !System.getProperty(PROPERTY, "").isEmpty();
    static final int CAPACITY = 1 << 18;
    static final long MAX_EVENTS = 50_000_000L;
    /** DH's shared executors, by the pos value their pool rows carry. Declared before INSTANCE, which reads it. */
    static final String[] POOLS = {"getWorldGenExecutor","getChunkToLodBuilderExecutor","getFileHandlerExecutor",
        "getNetworkCompressionExecutor","getUpdatePropagatorExecutor","getRenderLoadingExecutor"};
    private static final LabLodTrace INSTANCE = ENABLED ? new LabLodTrace(Paths.get(System.getProperty(PROPERTY)), side()) : null;

    final String side;
    final Path csv, json;
    private final long origin = System.nanoTime();
    private final Instant originInstant = Instant.now();
    private final ArrayBlockingQueue<Row> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Map<Long, String> threads = new ConcurrentHashMap<>();
    private final Map<String, Long> counts = new TreeMap<>();
    private final Thread writerThread;
    private BufferedWriter writer;
    private long written;
    private boolean capped;
    private volatile boolean closed;
    private String failure;

    static final class Row {
        final long nanos, thread, pos, a, b, c, d; final String op;
        Row(long nanos, long thread, String op, long pos, long a, long b, long c, long d) {
            this.nanos=nanos;this.thread=thread;this.op=op;this.pos=pos;this.a=a;this.b=b;this.c=c;this.d=d;
        }
    }

    LabLodTrace(Path directory, String side) {
        this.side=side;csv=directory.resolve("lod-"+side+".csv");json=directory.resolve("lod-"+side+".json");
        try {
            Files.createDirectories(directory);
            writer=Files.newBufferedWriter(csv,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            writer.write("nanos,thread,op,pos,detail,x,z,a,b,c,d\n");
        } catch(IOException e) { throw new IllegalStateException("Cannot open LOD trace "+csv,e); }
        writeSummary();
        writerThread=new Thread(this::drainLoop,"Cubic lab LOD trace writer");
        writerThread.setDaemon(true);writerThread.start();
        Thread sampler=new Thread(this::sampleLoop,"Cubic lab LOD pool sampler");
        sampler.setDaemon(true);sampler.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close,"Cubic lab LOD trace close"));
    }

    private static String side() {
        try { return net.minecraftforge.fml.relauncher.FMLLaunchHandler.side().isClient()?"client":"server"; }
        catch(Throwable e) { return "unknown"; }
    }

    // ===== producers =====
    public static void event(String op,long pos,long a,long b,long c,long d) {
        LabLodTrace t=INSTANCE;if(t!=null)t.add(op,pos,a,b,c,d);
    }
    public static void event(String op,long pos,long a) { event(op,pos,a,0,0,0); }
    public static void event(String op,long pos) { event(op,pos,0,0,0,0); }
    public static void mark(String op) { event(op,0,0,0,0,0); }

    void add(String op,long pos,long a,long b,long c,long d) {
        Thread thread=Thread.currentThread();long id=thread.getId();
        if(!threads.containsKey(id))threads.put(id,thread.getName());
        if(!queue.offer(new Row(System.nanoTime()-origin,id,op,pos,a,b,c,d)))dropped.incrementAndGet();
    }

    /** DH packs a section position as detail (8 bits), x (28 bits) and z (28 bits), low to high. */
    public static long encode(int detail,int x,int z) {
        return (detail&0x7FL)|((long)(x&0x0FFFFFFF)<<8)|((long)(z&0x0FFFFFFF)<<36);
    }
    public static int detail(long pos) { return (int)(pos&0x7F); }
    public static int x(long pos) { return ((int)((pos>>8)&0x0FFFFFFF)<<4)>>4; }
    public static int z(long pos) { return ((int)((pos>>36)&0x0FFFFFFF)<<4)>>4; }

    // ===== reflection for DH objects the lab cannot compile against =====
    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    public static Object field(Object owner,String name) {
        if(owner==null)return null;
        Field f=FIELDS.computeIfAbsent(owner.getClass().getName()+"#"+name,k->find(owner.getClass(),name));
        try { return f.get(owner); } catch(IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    public static Object call(Object owner,String name) {
        if(owner==null)return null;
        Method m=METHODS.computeIfAbsent(owner.getClass().getName()+"#"+name,k->{
            for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass())
                try { Method found=c.getDeclaredMethod(name);found.setAccessible(true);return found; } catch(NoSuchMethodException ignored) {}
            throw new IllegalStateException("No method "+name+" on "+owner.getClass());
        });
        try { return m.invoke(owner); } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static Field find(Class<?> type,String name) {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())
            try { Field f=c.getDeclaredField(name);f.setAccessible(true);return f; } catch(NoSuchFieldException ignored) {}
        throw new IllegalStateException("No field "+name+" on "+type);
    }
    public static long longField(Object owner,String name) { Object v=field(owner,name);return v==null?0:((Number)v).longValue(); }
    /** Retrieval outcome: 1 success, 2 split, 3 other state, -1 failure, -2 cancelled. */
    public static int outcome(Object result,Throwable failure) {
        if(failure!=null){
            Throwable cause=failure instanceof CompletionException&&failure.getCause()!=null?failure.getCause():failure;
            return cause instanceof CancellationException?-2:-1;
        }
        Object state=field(result,"state");
        if(state==null)return 3;
        String name=((Enum<?>)state).name();
        return name.equals("SUCCESS")?1:name.equals("REQUIRES_SPLITTING")?2:3;
    }

    // ===== DH executor sampler =====
    private static volatile boolean pools;
    /** Set by MixinLodThreadPools once DH has built its executors, cleared as it tears them down. */
    public static void poolsReady(boolean ready) { pools=ready; }

    /** Four times a second while DH's executors exist: one pool row per executor. */
    private void sampleLoop() {
        Class<?> util=null;
        while(!closed) {
            try {
                Thread.sleep(250);
                if(!pools)continue;
                if(util==null)util=Class.forName("com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil",true,LabLodTrace.class.getClassLoader());
                for(int i=0;i<POOLS.length;i++) {
                    Object executor=util.getMethod(POOLS[i]).invoke(null);
                    if(executor==null)continue;
                    add("pool",i,number(executor,"getQueueSize"),number(executor,"getRunningTaskCount"),
                        Boolean.TRUE.equals(call(executor,"canRun"))?1:0,number(executor,"getCompletedTaskCount"));
                }
            } catch(InterruptedException e) { return; }
            catch(ReflectiveOperationException|RuntimeException e) { failure="pool sampler: "+e; return; }
        }
    }
    private static long number(Object owner,String method) { Object v=call(owner,method);return v instanceof Number?((Number)v).longValue():-1; }

    // ===== writer =====
    private void drainLoop() {
        List<Row> batch=new ArrayList<>(8192);long lastFlush=System.nanoTime();
        while(!Thread.currentThread().isInterrupted()) {
            try {
                Row first=queue.poll(100,TimeUnit.MILLISECONDS);
                if(first!=null){batch.add(first);queue.drainTo(batch,8191);write(batch);batch.clear();}
                if(System.nanoTime()-lastFlush>=1_000_000_000L){flush();lastFlush=System.nanoTime();}
            } catch(InterruptedException e) { break; }
        }
    }
    private synchronized void write(List<Row> rows) {
        if(closed||writer==null)return;
        try {
            StringBuilder line=new StringBuilder(96);
            for(Row r:rows) {
                if(written>=MAX_EVENTS){capped=true;dropped.incrementAndGet();continue;}
                line.setLength(0);
                line.append(r.nanos).append(',').append(r.thread).append(',').append(r.op).append(',').append(r.pos).append(',')
                    .append(r.pos==0?0:detail(r.pos)).append(',').append(r.pos==0?0:x(r.pos)).append(',').append(r.pos==0?0:z(r.pos)).append(',')
                    .append(r.a).append(',').append(r.b).append(',').append(r.c).append(',').append(r.d).append('\n');
                writer.write(line.toString());written++;counts.merge(r.op,1L,Long::sum);
            }
        } catch(IOException e) { fail(e); }
    }
    synchronized void flush() {
        if(closed||writer==null)return;
        try { writer.flush(); } catch(IOException e) { fail(e); }
        writeSummary();
    }
    void close() {
        // The writer finishes the batch it holds before it sees the interrupt; only then drain the rest.
        writerThread.interrupt();
        try { writerThread.join(5000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        finish();
    }
    private synchronized void finish() {
        if(closed)return;
        List<Row> rest=new ArrayList<>();queue.drainTo(rest);write(rest);
        try { if(writer!=null)writer.close(); } catch(IOException e) { fail(e); }
        closed=true;writeSummary();
    }
    private void fail(IOException e) { failure=e.toString();try{writer.close();}catch(IOException ignored){}writer=null; }
    private synchronized void writeSummary() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("schema",1);out.put("side",side);out.put("process",java.lang.management.ManagementFactory.getRuntimeMXBean().getName());out.put("csv",csv.getFileName().toString());
        out.put("originMonotonicNanos",origin);out.put("originUtc",originInstant.toString());
        out.put("originEpochMicros",originInstant.getEpochSecond()*1_000_000L+originInstant.getNano()/1000);
        out.put("written",written);out.put("dropped",dropped.get());out.put("capped",capped);out.put("closed",closed);
        out.put("operations",new TreeMap<>(counts));out.put("threads",new TreeMap<>(threads));
        if(failure!=null)out.put("failure",failure);
        out.put("scope","Diagnostic DH LOD stage events. Times are this JVM's monotonic clock from originMonotonicNanos; "
            +"client and server align only through originEpochMicros (wall clock, same machine). "
            +"pos is DH's packed section position (detail, x, z also decoded); a-d are per-operation values. "
            +"pool rows: pos is the executor index into pools, a queued, b running, c 1 when DH lets it run, d completed.");
        out.put("pools",Arrays.asList(POOLS));
        Path temporary=json.resolveSibling(json.getFileName()+".tmp");
        try {
            Files.write(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(out).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary,json,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException e) { failure=e.toString(); }
    }
}
