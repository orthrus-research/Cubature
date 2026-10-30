package io.github.opencubicchunks.cubicchunks.core.server.delivery;

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
 * it was held; a later teleport that moves the player replaces a held one, and a held teleport
 * completes after TIMEOUT_TICKS even if its column is not prepared. Vanilla's corrections of a
 * client's moves never replace a held teleport ({@link #correct}), and neither does a teleport that
 * leaves the player where they are.
 */
public final class DeferredArrivals {
    /** A held teleport completes this long after it was held even if its column is not prepared. */
    public static final long TIMEOUT_TICKS = 600;

    /**
     * Whether a player may arrive in a column now. Asking may hand the column to the generation
     * workers (CubeProviderServer.arrivalReady).
     */
    @FunctionalInterface
    public interface Readiness {
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

        /** Whether this arrival leaves a player at (px, py, pz) where they are. */
        boolean at(double px, double py, double pz) {
            return Math.abs(x - px) < 1.0E-3 && Math.abs(y - py) < 1.0E-3 && Math.abs(z - pz) < 1.0E-3;
        }
    }

    private final WorldServer world;
    private final Readiness readiness;
    private final Map<UUID, Arrival> arrivals = new HashMap<>();
    /** Set while a held teleport completes, so it is not held again. */
    private boolean completing;
    /** Set while vanilla corrects a client's move, which neither holds nor replaces a teleport. */
    private boolean correcting;

    public DeferredArrivals(WorldServer world, Readiness readiness) {
        this.world = world;
        this.readiness = readiness;
    }

    /** Holds a teleport whose destination column is not ready. Returns whether it was held. */
    public boolean defer(EntityPlayerMP player, double x, double y, double z, float yaw, float pitch,
            Set<SPacketPlayerPosLook.EnumFlags> relative) {
        return defer(player.getUniqueID(), player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch,
                x, y, z, yaw, pitch, relative, world.getTotalWorldTime());
    }

    /**
     * Runs one of NetHandlerPlayServer.processPlayer's own setPlayerLocation calls: re-sending a
     * teleport the client has not confirmed, or putting the player back after a move it rejects. It
     * applies, and a held teleport stays held. A rejected move is put back where the player stood
     * before it, after processPlayer has already moved the player to where the client claimed; as an
     * ordinary teleport that read as a move elsewhere and dropped the hold.
     */
    public void correct(Runnable setPlayerLocation) {
        boolean outer = correcting;
        correcting = true;
        try {
            setPlayerLocation.run();
        } finally {
            correcting = outer;
        }
    }

    /** The hold for a player at (posX, posY, posZ), apart from the player and world objects. */
    boolean defer(UUID player, double posX, double posY, double posZ, float rotationYaw, float rotationPitch,
            double x, double y, double z, float yaw, float pitch, Set<SPacketPlayerPosLook.EnumFlags> relative, long now) {
        if (completing || correcting) return false;
        Arrival arrival = new Arrival(
                x + (relative.contains(SPacketPlayerPosLook.EnumFlags.X) ? posX : 0),
                y + (relative.contains(SPacketPlayerPosLook.EnumFlags.Y) ? posY : 0),
                z + (relative.contains(SPacketPlayerPosLook.EnumFlags.Z) ? posZ : 0),
                yaw + (relative.contains(SPacketPlayerPosLook.EnumFlags.Y_ROT) ? rotationYaw : 0),
                pitch + (relative.contains(SPacketPlayerPosLook.EnumFlags.X_ROT) ? rotationPitch : 0),
                now);
        if (readiness.ready(arrival.columnX, arrival.columnZ)) {
            if (!arrival.at(posX, posY, posZ)) arrivals.remove(player);
            return false;
        }
        arrivals.put(player, arrival);
        return true;
    }

    /** Whether a teleport of the player is held. */
    boolean holds(UUID player) {
        return arrivals.containsKey(player);
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
    public void tick(long now) {
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
