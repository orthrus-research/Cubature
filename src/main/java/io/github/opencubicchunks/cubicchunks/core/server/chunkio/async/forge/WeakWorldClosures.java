package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

/** Identity-based admission tombstones without retaining disposed worlds in a static strong set. */
final class WeakWorldClosures<W> {
    private final ArrayList<WeakReference<W>> worlds=new ArrayList<>();
    private volatile boolean any;
    synchronized void close(W world){if(!contains(world)){worlds.add(new WeakReference<>(world));any=true;}}
    boolean isClosed(W world){return any&&contains(world);}
    private synchronized boolean contains(W world){
        boolean found=false;
        for(Iterator<WeakReference<W>> it=worlds.iterator();it.hasNext();){W value=it.next().get();if(value==null)it.remove();else if(value==world)found=true;}
        any=!worlds.isEmpty();return found;
    }
}
