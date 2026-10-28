package io.github.opencubicchunks.cubicchunks.lab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import io.github.opencubicchunks.cubicchunks.core.network.PacketCubes;
import java.lang.reflect.Method;

/** Packet counters are safe on Netty threads; world state never moves into these hooks. */
public final class LabHooks {
    public static volatile PacketCounters serverPackets, clientPackets;
    public static final ThreadLocal<Invocation> encode = new ThreadLocal<>(), decode = new ThreadLocal<>(), apply = new ThreadLocal<>();
    private static String diagnosticObserver="not-initialized";
    private static final Method FINGERPRINT=LabCoreFeatures.optionalMethod(PacketCubes.class,"getCoordinateFingerprint");
    private static final Method CUBES=LabCoreFeatures.optionalMethod(PacketCubes.class,"getCubeCount");
    private static final Method SIZE=LabCoreFeatures.optionalMethod(PacketCubes.class,"getSerializedSize");
    public static void installDiagnostics() {
        diagnosticObserver=LabCoreFeatures.installObserver((proxy,method,args)-> {
            PacketCounters c;
            switch(method.getName()) {
                case "onSnapshot":LabDeliveryTrace.packet("packet-snapshot",args[0]);c=serverPackets;if(c!=null)c.snapshot((PacketCubes)args[0],(Long)args[1],(Long)args[2]);break;
                case "onQueued":LabDeliveryTrace.packet("packet-queued",args[0]);c=clientPackets;if(c!=null)c.queued((PacketCubes)args[0],(Long)args[1]);break;
                case "onApply":LabDeliveryTrace.packet((Boolean)args[4]?"packet-applied":"packet-apply-failed",args[0]);c=clientPackets;if(c!=null)c.applied((PacketCubes)args[0],(Long)args[1],(Long)args[2],(Long)args[3],(Boolean)args[4]);break;
                case "onSendStage":c=serverPackets;if(c!=null)c.sendStage((Long)args[0],(Long)args[1],(Long)args[2],(Integer)args[3],(Long)args[4],(Integer)args[5],(Long)args[6]);break;
                case "toString":return "Cubic lab bounded diagnostic observer";
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                default:throw new IllegalStateException("Unknown CC diagnostic callback "+method);
            }
            return null;
        });
    }
    public static final class Invocation {
        public final PacketCounters counters;
        public final long started = System.nanoTime();
        public final int index, cubes;
        public Invocation(PacketCounters counters, int index, int cubes) { this.counters = counters; this.index = index; this.cubes = cubes; }
    }
    public static final class PacketCounters {
        public final AtomicLong count = new AtomicLong(), bytes = new AtomicLong(), cubes = new AtomicLong(), nanos = new AtomicLong();
        public final AtomicLong applyCount = new AtomicLong(), applyNanos = new AtomicLong();
        private final Samples codec=new Samples(), applyWork=new Samples();
        private final Samples snapshot=Boolean.getBoolean("cubiclab.diagnostics")?new Samples():null;
        private final Samples queueWait=snapshot==null?null:new Samples(), observedApply=snapshot==null?null:new Samples();
        private final Samples send=snapshot==null?null:new Samples(), lighting=snapshot==null?null:new Samples();
        private final LabPacketTrace trace=snapshot==null?null:new LabPacketTrace();
        private long pendingMaximum, oldestMaximum, failedApplications;
        private final long[] packetCubeHistogram = new long[1025];
        private long unknownCubeCounts, maximumPayloadBytes;
        private boolean sealed;
        public synchronized void record(long duration, int size, int cubeCount) {
            if(sealed)return;
            count.incrementAndGet(); bytes.addAndGet(size); cubes.addAndGet(cubeCount); nanos.addAndGet(duration);
            if (cubeCount >= 0 && cubeCount < packetCubeHistogram.length) packetCubeHistogram[cubeCount]++;
            else unknownCubeCounts++;
            maximumPayloadBytes = Math.max(maximumPayloadBytes, size);
            codec.add(duration);
        }
        public synchronized void recordApply(long duration) {
            if(sealed)return;applyCount.incrementAndGet();applyNanos.addAndGet(duration);applyWork.add(duration);
        }
        synchronized void snapshot(PacketCubes packet,long start,long finish) {
            if(sealed||trace==null)return;snapshot.addAt(finish-start,finish);packetTrace(1,packet,start,finish,0,true);
        }
        synchronized void queued(PacketCubes packet,long queued) {
            if(sealed||trace==null)return;packetTrace(2,packet,queued,queued,queued,true);
        }
        synchronized void applied(PacketCubes packet,long queued,long start,long finish,boolean completed) {
            if(sealed||trace==null)return;
            if(queued!=0&&start>=queued)queueWait.addAt(start-queued,start);
            observedApply.addAt(finish-start,finish);if(!completed)failedApplications++;
            packetTrace(3,packet,start,finish,queued,completed);
        }
        synchronized void sendStage(long start,long finish,long light,int cubes,long bytes,int pending,long oldest) {
            if(sealed||trace==null)return;send.addAt(finish-start,finish);lighting.addAt(light,finish);
            pendingMaximum=Math.max(pendingMaximum,pending);oldestMaximum=Math.max(oldestMaximum,oldest);
            trace.add(4,0,0,cubes,bytes,start,finish,0,true,light,pending,oldest);
        }
        private void packetTrace(int stage,PacketCubes packet,long start,long finish,long queued,boolean completed) {
            Long fingerprint=LabCoreFeatures.optionalLong(FINGERPRINT,packet),cubeCount=LabCoreFeatures.optionalLong(CUBES,packet),size=LabCoreFeatures.optionalLong(SIZE,packet);
            trace.add(stage,System.identityHashCode(packet),fingerprint==null?LabCoreFeatures.UNAVAILABLE:fingerprint,
                    cubeCount==null?-1:cubeCount.intValue(),size==null?LabCoreFeatures.UNAVAILABLE:size,start,finish,queued,completed,0,0,0);
        }
        public synchronized Map<String, Object> summary() {
            sealed=true;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("packetCount", count.get()); data.put("serializedPayloadBytes", bytes.get());
            data.put("cubeCount", cubes.get()); data.put("serializationNanos", nanos.get());
            Map<String, Long> histogram = new LinkedHashMap<>();
            for (int i = 0; i < packetCubeHistogram.length; i++)
                if (packetCubeHistogram[i] != 0) histogram.put(Integer.toString(i), packetCubeHistogram[i]);
            data.put("packetCubeHistogram", histogram);
            data.put("unknownPacketCubeCounts", unknownCubeCounts);
            data.put("maximumPayloadBytes", maximumPayloadBytes);
            data.put("applyCount", applyCount.get()); data.put("applyNanos", applyNanos.get());
            data.put("codecDurations",codec.summary());data.put("applicationDurations",applyWork.summary());
            data.put("codecScope","toBytes on server or fromBytes on client only; constructor snapshot/WorldEncoder work is separate and measured only when diagnostic observer is enabled");
            data.put("diagnosticObserverAvailability",diagnosticObserver);
            if(trace!=null && diagnosticObserver.equals("installed")) {
                data.put("snapshotDurations",snapshot.summary());data.put("ownerQueueWaitDurations",queueWait.summary());
                data.put("observedApplicationDurations",observedApply.summary());data.put("ownerSendStageDurations",send.summary());data.put("sendLightingDurations",lighting.summary());
                data.put("pendingCubeMaximum",pendingMaximum);data.put("oldestPendingAgeMaximumNanos",oldestMaximum);
                data.put("failedApplications",failedApplications);data.put("diagnosticTrace",trace.summary());
                data.put("diagnosticScope","Observer overhead is included. Mixin and observer apply durations overlap; do not add. No cross-JVM transport delay or GPU-completion claim.");
            }
            data.put("byteScope", "PacketCubes payload before transport compression, excludes channel framing");
            return data;
        }
    }
}
