package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.CubeWatchEvent;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.entity.ICubicEntityTracker;
import io.github.opencubicchunks.cubicchunks.core.network.CubeSendDiagnostics;
import io.github.opencubicchunks.cubicchunks.core.network.PacketCubes;
import io.github.opencubicchunks.cubicchunks.core.network.PacketDispatcher;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Sends the cubes watchers queue for each player: per player in queue order and round-robin between
 * players, in PacketCubes batches (vanilla column packets for clients without CubicChunks) within the
 * tick's time and byte budget (maxCubeSendTimeMillis, maxCubeSendBytesPerTick, maxCubePacketBytes,
 * maxCubePacketCubes). A batch is published to its watchers before any callback runs; leashed
 * entities and CubeWatchEvent follow.
 */
final class CubeSender {
    /** What the sender asks of the player cube map. */
    interface Watchers {
        @Nullable CubeWatcher getCubeWatcher(CubePos pos);

        /** Whether this is the map's current instance of the player (not one from before a respawn). */
        boolean tracks(EntityPlayerMP player);
    }

    private final WorldServer world;
    private final VanillaNetworkHandler vanilla;
    private final Watchers watchers;
    private final PendingCubeSends<EntityPlayerMP, Cube> pending = new PendingCubeSends<>();
    private final CubeSendBudget budget = new CubeSendBudget();
    private final PlayerCubeDelivery.Publication<Cube, CubeWatcher, EntityPlayerMP> publication =
            new PlayerCubeDelivery.Publication<Cube, CubeWatcher, EntityPlayerMP>() {
                @Override public void markSent(Cube cube, CubeWatcher watcher, EntityPlayerMP player) {
                    if (isCurrent(cube, watcher) && watcher.containsPlayer(player)) watcher.onCubeSent(player);
                }

                @Override public void notifySent(Cube cube, CubeWatcher watcher, EntityPlayerMP player) {
                    // An earlier callback may remove/recreate this watcher or unsubscribe/rejoin the player.
                    if (!isCurrent(cube, watcher) || !watcher.isSentToPlayer(player)) return;
                    ((ICubicEntityTracker) world.getEntityTracker()).sendLeashedEntitiesInCube(player, cube);
                    // Entity tracking can itself invoke mod callbacks and change this subscription.
                    if (isCurrent(cube, watcher) && watcher.isSentToPlayer(player)) {
                        MinecraftForge.EVENT_BUS.post(new CubeWatchEvent(cube, cube.getCoords(), watcher, player));
                    }
                }

                private boolean isCurrent(Cube cube, CubeWatcher watcher) {
                    return watcher != null && watcher.getCube() == cube && watchers.getCubeWatcher(cube.getCoords()) == watcher;
                }
            };

    CubeSender(WorldServer world, Watchers watchers) {
        this.world = world;
        this.vanilla = ((ICubicWorldInternal.Server) world).getVanillaNetworkHandler();
        this.watchers = watchers;
    }

    void schedule(Cube cube, EntityPlayerMP player) {
        pending.add(player, cube, System.nanoTime());
    }

    void unschedule(Cube cube, EntityPlayerMP player) {
        pending.remove(player, cube);
    }

    void removePlayer(EntityPlayerMP player) {
        pending.removePlayer(player);
    }

    /** Sends queued cubes within this tick's time and byte budget, one packet per player in turn. */
    void send() {
        if (pending.isEmpty()) return;
        CubeSendDiagnostics.Observer observer = CubeSendDiagnostics.getObserver();
        int timeMillis = CubicChunksConfig.maxCubeSendTimeMillis;
        int packetTarget = CubicChunksConfig.maxCubePacketBytes;
        int packetCubes = CubicChunksConfig.maxCubePacketCubes;
        long started = observer != null || timeMillis > 0 ? System.nanoTime() : 0;
        budget.reset(started, TimeUnit.MILLISECONDS.toNanos(timeMillis), CubicChunksConfig.maxCubeSendBytesPerTick);
        // The byte budget and the send diagnostics count bytes, so packets must know their size up front.
        boolean exactBytes = observer != null || budget.limitsBytes();
        long lightingNanos = 0;
        int sent = 0;
        ArrayList<Cube> batch = new ArrayList<>(packetTarget > 0 ? Math.min(32, packetCubes) : packetCubes);
        ArrayList<CubeWatcher> batchWatchers = new ArrayList<>(packetTarget > 0 ? Math.min(32, packetCubes) : packetCubes);
        try {
            while (!pending.isEmpty() && budget.canSend(timeMillis > 0 ? System.nanoTime() : started)) {
                EntityPlayerMP player = pending.firstPlayer();
                if (!watchers.tracks(player)) {
                    pending.removePlayer(player);
                    continue;
                }
                batch.clear();
                long estimatedBytes = 6;
                while (batch.size() < packetCubes) {
                    Cube cube = pending.firstCube(player);
                    if (cube == null) break;
                    CubeWatcher watcher = watchers.getCubeWatcher(cube.getCoords());
                    if (watcher == null || watcher.getCube() != cube || !watcher.containsPlayer(player)) {
                        pending.remove(player, cube);
                        continue;
                    }
                    int cubeBytes = packetTarget > 0 ? PacketCubes.getUncompressedDataSize(cube) : 0;
                    boolean hasTiles = !cube.getTileEntityMap().isEmpty();
                    if (!batch.isEmpty() && packetTarget > 0 && (hasTiles || estimatedBytes + cubeBytes > packetTarget)) break;
                    pending.remove(player, cube);
                    batch.add(cube);
                    estimatedBytes += cubeBytes;
                    if (packetTarget > 0 && hasTiles) break; // Unknown NBT size: one indivisible cube may exceed the target.
                }
                if (batch.isEmpty()) continue;

                long lightStarted = observer == null ? 0 : System.nanoTime();
                ((ICubicWorldInternal) world).getLightingManager().onSendCubes(batch);
                if (observer != null) lightingNanos += System.nanoTime() - lightStarted;
                long bodyBytes = 0;
                PacketCubes packet = null;
                if (vanilla.hasCubicChunks(player)) {
                    packet = new PacketCubes(batch, exactBytes);
                    bodyBytes = Math.max(0, packet.getSerializedSize()); // Unknown (-1) only without exactBytes.
                }
                batchWatchers.clear();
                for (int i = 0; i < batch.size(); i++) batchWatchers.add(watchers.getCubeWatcher(batch.get(i).getCoords()));
                if (packet != null) {
                    PacketDispatcher.sendTo(packet, player);
                } else {
                    // Vanilla uses a different column protocol; its bytes are not CC message-body bytes.
                    vanilla.sendCubeLoadPackets(batch, player);
                }
                budget.sent(bodyBytes);
                sent += batch.size();
                PlayerCubeDelivery.publishBatch(batch, batchWatchers, player, publication);
                pending.rotate(player);
            }
        } finally {
            if (observer != null) {
                long finished = System.nanoTime();
                observer.onSendStage(started, finished, lightingNanos, sent, budget.bytes(),
                        pending.size(), pending.oldestAge(finished));
            }
        }
    }
}
