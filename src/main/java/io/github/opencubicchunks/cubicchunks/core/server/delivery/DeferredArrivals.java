package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import static io.github.opencubicchunks.cubicchunks.api.util.Coords.blockToCube;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

/**
 * Teleports held while generation workers prepare their destination column. The target and rotation
 * become absolute when held; replacement, departure and explicit forced moves release the request.
 * A long wait is reported, never completed merely because time passed. Vanilla's move corrections
 * ({@link #correct}) and teleports that leave a player in place preserve the held destination.
 */
public final class DeferredArrivals {
    static final long WATCHDOG_TICKS = 600;
    static final long MESSAGE_PERIOD = TimeUnit.SECONDS.toNanos(1);

    record Target(double x, double y, double z, float yaw, float pitch, int columnX, int columnZ) {
        Target(double x, double y, double z, float yaw, float pitch) {
            this(x, y, z, yaw, pitch, blockToCube(MathHelper.floor(x)), blockToCube(MathHelper.floor(z)));
        }

        boolean at(double px, double py, double pz) {
            return Math.abs(x - px) < 1.0E-3 && Math.abs(y - py) < 1.0E-3 && Math.abs(z - pz) < 1.0E-3;
        }
    }

    /** World operations kept outside the hold lifecycle so it can be tested without world objects. */
    interface Host {
        boolean immediate(int columnX, int columnZ);
        ConcurrentGeneration.ArrivalStatus status(int columnX, int columnZ);
        void hold(UUID player, int columnX, int columnZ);
        void release(UUID player);
        boolean present(UUID player);
        void complete(UUID player, Target target);
        void preparing(UUID player, boolean failed);
        void watchdog(UUID player, Target target, long ticks, ConcurrentGeneration.ArrivalStatus status);
    }

    private static final class Arrival {
        final Target target;
        final long since;
        long lastMessage = Long.MIN_VALUE;
        boolean overdue, failed;

        Arrival(Target target, long since) {
            this.target = target;
            this.since = since;
        }
    }

    private final Host host;
    @Nullable private final WorldServer world;
    private final Map<UUID, Arrival> arrivals = new HashMap<>();
    /** Set while a held teleport completes, so it is not held again. */
    private boolean completing;
    /** Set while vanilla corrects a client's move, which neither holds nor replaces a teleport. */
    private boolean correcting;

    DeferredArrivals(Host host) {
        this(null, host);
    }

    private DeferredArrivals(@Nullable WorldServer world, Host host) {
        this.world = world;
        this.host = host;
    }

    /** Holds teleports against this world's provider; generation and retention remain host-owned. */
    public static DeferredArrivals open(WorldServer world, CubeProviderServer provider) {
        return new DeferredArrivals(world, new Host() {
            @Override public boolean immediate(int x, int z) { return provider.arrivalImmediate(x, z); }
            @Override public ConcurrentGeneration.ArrivalStatus status(int x, int z) { return provider.arrivalStatus(x, z); }
            @Override public void hold(UUID player, int x, int z) { provider.retainArrivalColumn(player, x, z); }
            @Override public void release(UUID player) { provider.releaseArrivalColumn(player); }
            @Override public boolean present(UUID player) {
                EntityPlayerMP entity = entity(player);
                return entity != null && entity.world == world && entity.connection != null;
            }
            @Override public void complete(UUID player, Target target) {
                EntityPlayerMP entity = entity(player);
                if (entity != null) {
                    provider.installPreparedArrivalColumn(target.columnX(), target.columnZ());
                    entity.connection.setPlayerLocation(target.x(), target.y(), target.z(),
                            target.yaw(), target.pitch(), Collections.emptySet());
                }
            }
            @Override public void preparing(UUID player, boolean failed) {
                EntityPlayerMP entity = entity(player);
                if (entity != null) entity.sendStatusMessage(new TextComponentTranslation(failed
                        ? "cubicchunks.arrival.preparation_failed" : "cubicchunks.arrival.preparing"), true);
            }
            @Override public void watchdog(UUID player, Target target, long ticks, ConcurrentGeneration.ArrivalStatus status) {
                CubicChunks.LOGGER.warn("Cubature: teleport of {} to column ({}, {}) remains held after {} ticks; {}: {}",
                        player, target.columnX(), target.columnZ(), ticks, status.state(), status.detail());
            }
            private EntityPlayerMP entity(UUID player) {
                return world.getMinecraftServer().getPlayerList().getPlayerByUUID(player);
            }
        });
    }

    /** Holds a teleport whose destination column is not ready. Returns whether it was held. */
    public boolean defer(EntityPlayerMP player, double x, double y, double z, float yaw, float pitch,
            Set<SPacketPlayerPosLook.EnumFlags> relative) {
        return defer(player.getUniqueID(), player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch,
                x, y, z, yaw, pitch, relative, player.world.getTotalWorldTime());
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

    /** The hold for a player apart from the player and world objects. */
    boolean defer(UUID player, double posX, double posY, double posZ, float rotationYaw, float rotationPitch,
            double x, double y, double z, float yaw, float pitch, Set<SPacketPlayerPosLook.EnumFlags> relative, long now) {
        if (completing || correcting) return false;
        Target target = new Target(
                x + (relative.contains(SPacketPlayerPosLook.EnumFlags.X) ? posX : 0),
                y + (relative.contains(SPacketPlayerPosLook.EnumFlags.Y) ? posY : 0),
                z + (relative.contains(SPacketPlayerPosLook.EnumFlags.Z) ? posZ : 0),
                yaw + (relative.contains(SPacketPlayerPosLook.EnumFlags.Y_ROT) ? rotationYaw : 0),
                pitch + (relative.contains(SPacketPlayerPosLook.EnumFlags.X_ROT) ? rotationPitch : 0));
        if (host.immediate(target.columnX(), target.columnZ())) {
            if (!target.at(posX, posY, posZ)) {
                Arrival held = remove(player);
                if (held != null) replaced(player, held.target, target);
            }
            return false;
        }
        Arrival next = new Arrival(target, now);
        Arrival held = arrivals.put(player, next);
        if (held != null) {
            next.lastMessage = held.lastMessage;
            replaced(player, held.target, target);
        }
        // The host replaces this owner's request atomically, retaining a shared same-column result.
        host.hold(player, target.columnX(), target.columnZ());
        return true;
    }

    private static void replaced(UUID player, Target held, Target by) {
        String callers = StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().equals(DeferredArrivals.class.getName()))
                .limit(4)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                .collect(Collectors.joining(" < ")));
        CubicChunks.LOGGER.info("Cubature: a teleport of {} to ({}, {}, {}) replaced the one held to ({}, {}, {}), from {}",
                player, by.x(), by.y(), by.z(), held.x(), held.y(), held.z(), callers);
    }

    public boolean holds(UUID player) {
        return arrivals.containsKey(player);
    }

    /** Teleports at once, generating the destination column on this thread if it must (scripted moves). */
    public void teleportNow(EntityPlayerMP player, double x, double y, double z, float yaw, float pitch) {
        teleportNow(player.getUniqueID(), new Target(x, y, z, yaw, pitch));
    }

    void teleportNow(UUID player, Target target) {
        remove(player);
        complete(player, target);
    }

    /** Drops a departed player's hold immediately, including its urgent request and retained result. */
    public void departed(UUID player) {
        Arrival held = remove(player);
        if (held != null) dropped(player, held.target);
    }

    /** Releases every hold when its world stops. */
    public void close() {
        for (UUID player : arrivals.keySet()) host.release(player);
        arrivals.clear();
    }

    private Arrival remove(UUID player) {
        Arrival held = arrivals.remove(player);
        if (held != null) host.release(player);
        return held;
    }

    private static void dropped(UUID player, Target target) {
        CubicChunks.LOGGER.info("Cubature: dropped the teleport of {} held to ({}, {}, {}); the player left this world",
                player, target.x(), target.y(), target.z());
    }

    /** Completes ready destinations; elapsed time only triggers diagnostics and player messages. */
    public void tick(long now) {
        if (world != null) world.profiler.startSection("arrivals");
        try {
            tick(now, System.nanoTime());
        } finally {
            if (world != null) world.profiler.endSection();
        }
    }

    void tick(long now, long nanoTime) {
        Iterator<Map.Entry<UUID, Arrival>> it = arrivals.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Arrival> entry = it.next();
            UUID player = entry.getKey();
            Arrival arrival = entry.getValue();
            Target target = arrival.target;
            if (!host.present(player)) {
                it.remove();
                host.release(player);
                dropped(player, target);
                continue;
            }
            ConcurrentGeneration.ArrivalStatus status = host.status(target.columnX(), target.columnZ());
            if (status.state() == ConcurrentGeneration.ArrivalState.READY) {
                it.remove();
                // Retention extends through generateColumn and installation, including exceptions.
                try {
                    complete(player, target);
                } finally {
                    host.release(player);
                }
                continue;
            }
            long waited = now - arrival.since;
            boolean failed = status.state() == ConcurrentGeneration.ArrivalState.FAILED;
            boolean overdue = waited >= WATCHDOG_TICKS;
            if ((failed && !arrival.failed) || (overdue && !arrival.overdue))
                host.watchdog(player, target, waited, status);
            arrival.failed = failed;
            arrival.overdue |= overdue;
            if (arrival.lastMessage == Long.MIN_VALUE || nanoTime - arrival.lastMessage >= MESSAGE_PERIOD) {
                arrival.lastMessage = nanoTime;
                host.preparing(player, failed);
            }
        }
    }

    private void complete(UUID player, Target target) {
        boolean outer = completing;
        completing = true;
        try {
            host.complete(player, target);
        } finally {
            completing = outer;
        }
    }
}
