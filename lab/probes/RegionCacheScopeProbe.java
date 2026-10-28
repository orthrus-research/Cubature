import cubicchunks.regionlib.api.region.*;
import cubicchunks.regionlib.api.region.key.IKey;
import cubicchunks.regionlib.impl.*;
import cubicchunks.regionlib.impl.save.*;
import cubicchunks.regionlib.lib.provider.SharedCachedRegionProvider;
import java.lang.reflect.*;
import java.util.*;

/** Actual pinned cache + save-section traversal, fake file handles only. */
public class RegionCacheScopeProbe {
    static class Counts {int flush,close;}
    @SuppressWarnings("unchecked") static <K extends IKey<K>> SharedCachedRegionProvider<K> provider(Counts count){
        IRegion<K> region=(IRegion<K>)Proxy.newProxyInstance(RegionCacheScopeProbe.class.getClassLoader(),new Class<?>[]{IRegion.class},(p,m,a)->{
            if(m.getName().equals("flush"))count.flush++;
            if(m.getName().equals("close"))count.close++;
            if(m.getReturnType()==boolean.class)return false;
            if(m.getReturnType()==Optional.class)return Optional.empty();
            return null;
        });
        IRegionProvider<K> source=(IRegionProvider<K>)Proxy.newProxyInstance(RegionCacheScopeProbe.class.getClassLoader(),new Class<?>[]{IRegionProvider.class},(p,m,a)->{
            if(m.getName().equals("getExistingRegion"))return Optional.empty();
            if(m.getName().equals("getRegion"))return region;
            if(m.getName().equals("hashCode"))return System.identityHashCode(p);
            if(m.getName().equals("equals"))return p==a[0];
            return null;
        });
        return new SharedCachedRegionProvider<>(source);
    }
    public static void main(String[] args)throws Exception{
        Counts a=new Counts(),b=new Counts();
        SharedCachedRegionProvider<EntryLocation2D> a2=provider(a),a2ext=provider(new Counts());
        SharedCachedRegionProvider<EntryLocation3D> a3=provider(a),a3ext=provider(new Counts()),b3=provider(b);
        a3.forRegion(new EntryLocation3D(0,0,0),r->{});
        b3.forRegion(new EntryLocation3D(0,0,0),r->{});
        SaveCubeColumns worldA=new SaveCubeColumns(new SaveSection2D(a2,a2ext),new SaveSection3D(a3,a3ext));
        worldA.flush();
        if(a.flush!=4||b.flush!=4)throw new AssertionError("Unexpected flush counts "+a.flush+","+b.flush);
        System.out.println("CONFIRMED Java="+System.getProperty("java.version")+" world-A storage flush: A-region flushes="+a.flush+", unrelated B-region flushes="+b.flush);
        worldA.close();
        if(a.close!=1||b.close!=1)throw new AssertionError("Unexpected close counts");
        System.out.println("CONFIRMED world-A storage close: A-region closes="+a.close+", unrelated B-region closes="+b.close);
    }
}
