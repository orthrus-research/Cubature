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

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * A host-selected save-level directory for discardable derived generator data, available during biome-provider
 * construction without depending on provider initialization. Worlds using the same save location share this root.
 * The host owns its location and the world's lifecycle. Consumers namespace files by kind and all inputs that
 * determine their content, and must release their opened resources before the world closes. A request creates no
 * directories or resources.
 */
public final class WorldDerivedDataDirectory {
    private WorldDerivedDataDirectory() { }

    /**
     * Requests a normalized absolute directory before the cube generator exists. Clients, nonserver worlds and
     * worlds without a usable save location receive null and keep their derived data in memory only.
     */
    @Nullable public static Path request(@Nullable World world) {
        if (world == null || world.isRemote || !(world instanceof WorldServer)) return null;
        Request event = new Request(world);
        MinecraftForge.EVENT_BUS.post(event);
        return event.directory;
    }

    /** Host request on {@link MinecraftForge#EVENT_BUS}; this does not publish a world lifecycle event. */
    public static final class Request extends Event {
        private final World world;
        @Nullable private Path directory;

        private Request(World world) { this.world = world; }

        public World getWorld() { return world; }

        /** Supplies the host-owned root, or null when only in-memory derived data is available. */
        public void offer(@Nullable Path directory) { this.directory = directory; }
    }
}
