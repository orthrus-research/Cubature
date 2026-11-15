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
package io.github.opencubicchunks.cubicchunks.core.client.source;

import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ClientPreviewInputs;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ClientPreviewOffer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreviewFactory;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewFactories;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.worldgen.SharedTerrainPreviews;
import io.github.opencubicchunks.cubicchunks.core.worldgen.WithdrawablePreview;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import javax.annotation.Nullable;

/**
 * The client's terrain preview for its current world (docs/CUBE-SOURCE.md, section 8): borrowed
 * from the integrated server's generator in single player, otherwise built by the generator's
 * registered factory from what the server sent at join.
 *
 * <p>Everything here runs on the client thread, except the factory itself.
 */
public final class ClientTerrainPreviews {

    public static final ClientTerrainPreviews INSTANCE = new ClientTerrainPreviews();

    @Nullable private World owner;
    @Nullable private WithdrawablePreview preview;
    private boolean borrowed;
    /** Moves whenever the owner changes, so a build finishing for an earlier world is dropped. */
    private long generation;
    private boolean changed;

    private ClientTerrainPreviews() {
    }

    /**
     * The server told this client that its world is cubic. A client of its own integrated server
     * borrows that server's generator; no factory runs.
     */
    public void joined(World world) {
        adopt(world);
        if (preview != null || !integrated()) {
            return;
        }
        WorldServer serverWorld = DimensionManager.getWorld(world.provider.getDimension());
        if (!(serverWorld instanceof ICubicWorldServer)) {
            return;
        }
        ICubeGenerator generator = ((ICubicWorldServer) serverWorld).getCubeGenerator();
        if (generator instanceof ICubicTerrainPreview) {
            preview = SharedTerrainPreviews.lend(serverWorld, (ICubicTerrainPreview) generator);
            borrowed = preview != null;
            changed |= borrowed;
            if (borrowed) {
                CubicChunks.LOGGER.info("Terrain preview for dimension {}: the integrated server's generator",
                        world.provider.getDimension());
            }
        }
    }

    /** The server's offer for a dimension arrived, or with a null offer its notice that the seed is withheld. */
    public void offered(World world, int dimension, @Nullable ClientPreviewOffer offer) {
        if (world.provider.getDimension() != dimension) {
            return;
        }
        adopt(world);
        if (preview != null || integrated()) {
            return;
        }
        if (offer == null) {
            CubicChunks.LOGGER.info("Terrain preview for dimension {}: the server withholds its seed", dimension);
            return;
        }
        ICubicTerrainPreviewFactory factory = TerrainPreviewFactories.get(offer.factory());
        if (factory == null) {
            CubicChunks.LOGGER.info("Terrain preview for dimension {}: no factory named {} is registered", dimension,
                    offer.factory());
            return;
        }
        // No build pool and no derived-data directory yet: regional builds run on the sampling
        // thread and stay in memory (docs/CUBE-SOURCE.md, section 8, what is not built).
        ClientPreviewInputs inputs = new ClientPreviewInputs(dimension, offer, null, null);
        long mine = generation;
        Thread build = new Thread(() -> {
            ICubicTerrainPreview built = null;
            try {
                built = factory.create(inputs);
            } catch (RuntimeException | Error failure) {
                CubicChunks.LOGGER.error("Terrain preview factory " + offer.factory() + " failed", failure);
            }
            ICubicTerrainPreview result = built;
            Minecraft.getMinecraft().addScheduledTask(() -> built(mine, dimension, offer.factory(), result));
        }, "Cubature client terrain preview");
        build.setDaemon(true);
        build.start();
    }

    private void built(long forGeneration, int dimension, String factory, @Nullable ICubicTerrainPreview result) {
        if (forGeneration != generation || preview != null) {
            return;
        }
        if (result == null) {
            CubicChunks.LOGGER.info("Terrain preview for dimension {}: factory {} declined", dimension, factory);
            return;
        }
        preview = new WithdrawablePreview(result);
        borrowed = false;
        changed = true;
        CubicChunks.LOGGER.info("Terrain preview for dimension {}: built by factory {}", dimension, factory);
    }

    /** The preview of the given client world, or null: none offered, declined, not built yet or withdrawn. */
    @Nullable
    public ICubicTerrainPreview preview(@Nullable World world) {
        return world != null && world == owner && preview != null && !preview.withdrawn() ? preview : null;
    }

    /**
     * Once per client tick: drops the preview of a world that is no longer the client's, and
     * notices one the integrated server took back.
     */
    public void tick(@Nullable World current) {
        if (owner != null && current != owner) {
            close();
        } else if (preview != null && preview.withdrawn()) {
            preview = null;
            borrowed = false;
            changed = true;
        }
    }

    /** Whether the preview appeared or went since the last call. */
    public boolean takeChanged() {
        boolean was = changed;
        changed = false;
        return was;
    }

    private void adopt(World world) {
        if (owner != world) {
            close();
            owner = world;
        }
    }

    private void close() {
        if (preview != null) {
            // Without waiting: a column in flight finishes on its own maps, and an integrated
            // server waits for its generator's columns itself before it closes the world.
            if (borrowed) {
                SharedTerrainPreviews.giveBack(preview);
            } else {
                preview.revoke();
            }
            preview = null;
            borrowed = false;
            changed = true;
        }
        owner = null;
        generation++;
    }

    private static boolean integrated() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft.isIntegratedServerRunning() && minecraft.getIntegratedServer() != null;
    }
}
