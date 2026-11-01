/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.netty.buffer.ByteBuf;
import java.util.concurrent.atomic.AtomicInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH's 1.12.2 plugin channel builds each received message on the Netty thread, and a trackable message's constructor
 * asserts that a DH world is loaded. On a dedicated server DH closes its world at FMLServerStoppingEvent, before the
 * server disconnects its players, so a client's full-data requests still arriving then fail to decode and Forge drops
 * the connection with "A fatal error has occurred". A client leaving a server can meet the same with late answers.
 * With no DH world loaded, the wrapper is left without a message: DH's handler already skips such a wrapper, and its
 * server and client APIs would have ignored the message without a world anyway.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.forge112.ForgePluginPacketSender$MessageWrapper", remap = false)
public abstract class MixinDistantHorizonsNoWorldMessages {
    @Unique private static final AtomicInteger cubicchunks$dropped = new AtomicInteger();

    @Inject(method = "fromBytes", at = @At("HEAD"), cancellable = true, remap = false)
    private void cubicchunks$dropWithoutWorld(ByteBuf buf, CallbackInfo callback) {
        if (SharedApi.getEnvironment() != null) {
            return;
        }
        int id = buf.isReadable() ? buf.getByte(buf.readerIndex()) : -1;
        buf.skipBytes(buf.readableBytes());
        callback.cancel();
        int dropped = cubicchunks$dropped.incrementAndGet();
        if (dropped == 1 || dropped % 64 == 0) {
            CubicChunks.LOGGER.info("CC/DH dropped a Distant Horizons message (id {}) received with no DH world loaded; {} so far",
                    id, dropped);
        }
    }
}
