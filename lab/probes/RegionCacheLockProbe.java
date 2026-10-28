import cubicchunks.regionlib.lib.provider.SharedCachedRegionProvider;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.api.region.IRegion;
import cubicchunks.regionlib.api.region.IRegionProvider;
import java.lang.reflect.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/** Separate-process lock-order probe against the unchanged pinned dependency; no real files/worlds. */
public class RegionCacheLockProbe {
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        IRegion<EntryLocation3D> region=(IRegion<EntryLocation3D>)Proxy.newProxyInstance(RegionCacheLockProbe.class.getClassLoader(),new Class<?>[]{IRegion.class},(p,m,a)->{
            if(m.getName().equals("toString"))return "fake region";
            if(m.getReturnType()==boolean.class)return false;
            if(m.getReturnType()==Optional.class)return Optional.empty();
            return null;
        });
        IRegionProvider<EntryLocation3D> source=(IRegionProvider<EntryLocation3D>)Proxy.newProxyInstance(RegionCacheLockProbe.class.getClassLoader(),new Class<?>[]{IRegionProvider.class},(p,m,a)->{
            if(m.getName().equals("getExistingRegion"))return Optional.empty();
            if(m.getName().equals("getRegion"))return region;
            if(m.getName().equals("hashCode"))return System.identityHashCode(p);
            if(m.getName().equals("equals"))return p==a[0];
            return null;
        });
        SharedCachedRegionProvider<EntryLocation3D> provider=new SharedCachedRegionProvider<>(source);
        for(int i=0;i<257;i++)provider.forRegion(new EntryLocation3D(i*16,0,0),r->{});
        Field f=SharedCachedRegionProvider.class.getDeclaredField("lock");f.setAccessible(true);
        ReentrantReadWriteLock lock=(ReentrantReadWriteLock)f.get(null);
        CountDownLatch monitorHeld=new CountDownLatch(1);
        Thread flusher=new Thread(()->{
            synchronized(SharedCachedRegionProvider.class){
                monitorHeld.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!lock.isWriteLocked() && System.nanoTime()<deadline)Thread.yield();
                try{SharedCachedRegionProvider.flushRegions();}catch(Exception e){throw new RuntimeException(e);}
            }
        },"probe-flush-class-then-write");
        Thread inserter=new Thread(()->{
            try{monitorHeld.await();provider.forRegion(new EntryLocation3D(10000,0,0),r->{});}catch(Exception e){throw new RuntimeException(e);}
        },"probe-overflow-write-then-class");
        flusher.setDaemon(true);inserter.setDaemon(true);flusher.start();inserter.start();
        ThreadMXBean bean=ManagementFactory.getThreadMXBean();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);long[] ids=null;
        while(System.nanoTime()<deadline && (ids=bean.findDeadlockedThreads())==null)Thread.sleep(10);
        if(ids==null)throw new AssertionError("No deadlock reproduced");
        System.out.println("CONFIRMED isolated pinned-RegionLib lock-order deadlock; Java="+System.getProperty("java.version"));
        for(ThreadInfo info:bean.getThreadInfo(ids,true,true))System.out.println(info);
    }
}
