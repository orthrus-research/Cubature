package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.DeliveryMode;

import javax.annotation.Nullable;

/**
 * The running server's delivery mode. It is read from the config as the server starts, so every
 * dimension the server loads shares it; a config change applies from the next server start.
 */
public final class ServerDeliveryMode {
    @Nullable private static volatile DeliveryMode mode;

    private ServerDeliveryMode() { }

    public static void resolve() {
        mode = CubicChunksConfig.deliveryMode;
        CubicChunks.LOGGER.info("Cubature delivery mode for this server: {}", mode);
    }

    public static void clear() { mode = null; }

    /** The mode the starting or running server resolved; worlds read it as they are built. */
    public static DeliveryMode get() {
        DeliveryMode resolved = mode;
        if (resolved == null) {
            throw new IllegalStateException("Cubature resolves the delivery mode as the server starts; no server has started");
        }
        return resolved;
    }
}
