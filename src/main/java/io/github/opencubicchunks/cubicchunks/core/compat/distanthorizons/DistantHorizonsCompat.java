package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** Optional client hook. Loading CC without DH must not resolve any DH classes. */
public final class DistantHorizonsCompat {
    private static Consumer<Chunk> changedColumn;
    private static Consumer<Chunk> changedServerColumn;
    private static final Map<World,Boolean> remotePreviews=Collections.synchronizedMap(new WeakHashMap<>());
    private DistantHorizonsCompat() {}
    public static void install(Consumer<Chunk> listener) { changedColumn = listener; }
    public static void installServer(Consumer<Chunk> listener) { changedServerColumn = listener; }
    public static boolean hasServerIntegration() { return changedServerColumn!=null; }
    private static Predicate<Chunk> feedServerNow;
    private static IntSupplier pendingServerUpdates;
    public static void installServerFeed(Predicate<Chunk> feed, IntSupplier pending) { feedServerNow=feed; pendingServerUpdates=pending; }
    /**
     * Hands a loaded server column to DH now, as the server's native feed does a few a tick (a pregenerator's
     * columns unload before that feed reaches them). False without DH or its level for that world.
     */
    public static boolean feedServerColumnNow(Chunk column) { Predicate<Chunk> f=feedServerNow; return f!=null && f.test(column); }
    /** Chunk updates DH has queued and not yet turned into LOD data, or 0 without DH. */
    public static int pendingServerUpdates() { IntSupplier p=pendingServerUpdates; return p==null?0:p.getAsInt(); }
    public static void setRemotePreview(World world,boolean supported) {
        if (supported) remotePreviews.put(world,Boolean.TRUE);else remotePreviews.remove(world);
    }
    public static boolean hasTerrainPreview(World world) {
        if (!((ICubicWorld)world).isCubicWorld()) return false;
        return world.isRemote?Boolean.TRUE.equals(remotePreviews.get(world)):
                ((ICubicWorldServer)world).getCubeGenerator() instanceof ICubicTerrainPreview;
    }
    /** Block contents changed (an edit): always handed to DH. */
    public static void columnChanged(Chunk column) {
        Consumer<Chunk> listener=column.getWorld().isRemote?changedColumn:changedServerColumn;
        if (listener != null) listener.accept(column);
    }

    /**
     * A column was loaded, received, relit or had its heightmap refreshed without a block edit. Under
     * EDITS a terrain preview already gave DH this generated surface, so only worlds without a preview
     * (or a client whose server has none) still feed it.
     */
    public static void columnLoaded(Chunk column) {
        if (CubicChunksConfig.distantHorizonsNativeFeed==CubicChunksConfig.DistantHorizonsNativeFeed.EDITS
                && hasTerrainPreview(column.getWorld())) return;
        columnChanged(column);
    }
}
