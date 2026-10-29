package io.github.opencubicchunks.cubicchunks.core.server;

import static io.github.opencubicchunks.cubicchunks.api.util.Coords.blockToCube;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Teleports held while generation workers prepare their destination column. A teleport
 * (NetHandlerPlayServer.setPlayerLocation) into a column that is neither loaded nor saved is held, so
 * the server thread does not generate it on the spot and every player keeps ticking. The player stays
 * where they are until tick completes the teleport, with its target and rotation made absolute when
 * it was held; a later teleport replaces a held one, and a held teleport completes after
 * TIMEOUT_TICKS even if its column is not prepared.
 */
public final class DeferredArrivals {
    /** A held teleport completes this long after it was held even if its column is not prepared. */
    static final long TIMEOUT_TICKS = 600;

    /**
     * Whether a player may arrive in a column now. Asking may hand the column to the generation
     * workers (CubeProviderServer.arrivalReady).
     */
    @FunctionalInterface
    interface Readiness {
        boolean ready(int columnX, int columnZ);
    }

    private static final class Arrival {
        final double x, y, z;
        final float yaw, pitch;
        final int columnX, columnZ;
        final long since;

        Arrival(double x, double y, double z, float yaw, float pitch, long since) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.since = since;
            columnX = blockToCube(MathHelper.floor(x));
            columnZ = blockToCube(MathHelper.floor(z));
        }
    }

    private final WorldServer world;
    private final Readiness readiness;
    private final Map<UUID, Arrival> arrivals = new HashMap<>();
    /** Set while a held teleport completes, so it is not held again. */
    private boolean completing;

    DeferredArrivals(WorldServer world, Readiness readiness) {
        this.world = world;
        this.readiness = readiness;
    }

    /** Holds a teleport whose destination column is not ready. Returns whether it was held. */
    public boolean defer(EntityPlayerMP player, double x, double y, double z, float yaw, float pitch,
            Set<SPacketPlayerPosLook.EnumFlags> relative) {
        if (completing) return false;
        Arrival arrival = new Arrival(
                x + (relative.contains(SPacketPlayerPosLook.EnumFlags.X) ? player.posX : 0),
                y + (relative.contains(SPacketPlayerPosLook.EnumFlags.Y) ? player.posY : 0),
                z + (relative.contains(SPacketPlayerPosLook.EnumFlags.Z) ? player.posZ : 0),
                yaw + (relative.contains(SPacketPlayerPosLook.EnumFlags.Y_ROT) ? player.rotationYaw : 0),
                pitch + (relative.contains(SPacketPlayerPosLook.EnumFlags.X_ROT) ? player.rotationPitch : 0),
                world.getTotalWorldTime());
        if (readiness.ready(arrival.columnX, arrival.columnZ)) {
            arrivals.remove(player.getUniqueID());
            return false;
        }
        arrivals.put(player.getUniqueID(), arrival);
        return true;
    }

    /** Teleports at once, generating the destination column on this thread if it must (scripted moves). */
    public void teleportNow(EntityPlayerMP player, double x, double y, double z, float yaw, float pitch) {
        arrivals.remove(player.getUniqueID());
        completing = true;
        try {
            player.connection.setPlayerLocation(x, y, z, yaw, pitch);
        } finally {
            completing = false;
        }
    }

    /** Completes each held teleport whose column is prepared or whose wait has run out. */
    void tick(long now) {
        if (arrivals.isEmpty()) return;
        world.profiler.startSection("arrivals");
        Iterator<Map.Entry<UUID, Arrival>> it = arrivals.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Arrival> entry = it.next();
            Arrival a = entry.getValue();
            EntityPlayerMP player = world.getMinecraftServer().getPlayerList().getPlayerByUUID(entry.getKey());
            if (player == null || player.world != world || player.connection == null) {
                it.remove();
                continue;
            }
            if (now - a.since < TIMEOUT_TICKS && !readiness.ready(a.columnX, a.columnZ)) continue;
            it.remove();
            completing = true;
            try {
                player.connection.setPlayerLocation(a.x, a.y, a.z, a.yaw, a.pitch, Collections.emptySet());
            } finally {
                completing = false;
            }
        }
        world.profiler.endSection();
    }
}
