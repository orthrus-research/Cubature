/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.api.worldgen;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.concurrent.ForkJoinPool;

import javax.annotation.Nullable;

/**
 * A world's host-owned pool for pure generator work, available while its biome provider is being constructed.
 * The cube provider later offers the same pool through {@link ICubeGenerator#useBuildPool}. The host sizes and
 * stops it; callers must not shut it down. A null pool means that work stays on the calling thread.
 */
public final class WorldBuildPool {
    private WorldBuildPool() { }

    /**
     * Requests the server world's pool before its cube generator exists. Repeated requests return the same pool,
     * including when the configured target is zero. Requests after the world closes are rejected. Clients receive
     * null. If world construction fails after this call, notify the host with {@link #abandon(World)}.
     */
    @Nullable public static ForkJoinPool request(World world) {
        if (world.isRemote || !(world instanceof WorldServer)) return null;
        Request event = new Request(world);
        try {
            MinecraftForge.EVENT_BUS.post(event);
        } catch (RuntimeException | Error failure) {
            try { abandon(world); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        return event.pool;
    }

    /**
     * Notifies the host that early construction failed. A pool not yet adopted by the cube provider is retired;
     * an adopted pool stays available to its generator until normal world shutdown. Repeated notifications are safe.
     */
    public static void abandon(World world) {
        if (!world.isRemote && world instanceof WorldServer) MinecraftForge.EVENT_BUS.post(new Abandoned(world));
    }

    /** Host request on {@link MinecraftForge#EVENT_BUS}; it is neither cancellable nor a generation request. */
    public static final class Request extends Event {
        private final World world;
        @Nullable private ForkJoinPool pool;

        private Request(World world) { this.world = world; }

        public World getWorld() { return world; }

        /** Supplies the world's pool, or null when the host has disabled parallel builds. */
        public void offer(@Nullable ForkJoinPool pool) { this.pool = pool; }
    }

    /** Failed-construction notification on {@link MinecraftForge#EVENT_BUS}; ignored after pool adoption. */
    public static final class Abandoned extends Event {
        private final World world;

        private Abandoned(World world) { this.world = world; }

        public World getWorld() { return world; }
    }
}
