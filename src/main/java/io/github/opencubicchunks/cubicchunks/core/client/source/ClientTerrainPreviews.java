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
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
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
    /** The build pool of the preview this client built, shut down with it; none for a borrowed one. */
    @Nullable private ClientPreviewBuildPool buildPool;
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
        // The preview's own small build pool, never the sampling pool's threads; no derived-data
        // directory yet, so what it builds stays in memory (docs/CUBE-SOURCE.md, section 8).
        ClientPreviewBuildPool buildPoolFor = new ClientPreviewBuildPool(ClientPreviewBuildPool.size(ClientPreviewWork.size(
                CubicChunksConfig.clientPreviewThreads, Runtime.getRuntime().availableProcessors())),
                "dimension " + dimension);
        ClientPreviewInputs inputs = new ClientPreviewInputs(dimension, offer, buildPoolFor.pool(), null);
        long mine = generation;
        Thread build = new Thread(() -> {
            ICubicTerrainPreview built = null;
            try {
                built = factory.create(inputs);
            } catch (RuntimeException | Error failure) {
                CubicChunks.LOGGER.error("Terrain preview factory " + offer.factory() + " failed", failure);
            }
            ICubicTerrainPreview result = built;
            Minecraft.getMinecraft().addScheduledTask(() -> built(mine, dimension, offer.factory(), result, buildPoolFor));
        }, "Cubature client terrain preview");
        build.setDaemon(true);
        build.start();
    }

    private void built(long forGeneration, int dimension, String factory, @Nullable ICubicTerrainPreview result,
            ClientPreviewBuildPool build) {
        if (forGeneration != generation || preview != null) {
            // Built for a world that is gone, or after another: neither it nor its pool is kept.
            build.shutdown(0);
            return;
        }
        if (result == null) {
            build.shutdown(0);
            CubicChunks.LOGGER.info("Terrain preview for dimension {}: factory {} declined", dimension, factory);
            return;
        }
        preview = new WithdrawablePreview(result);
        buildPool = build;
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
            stopBuildPool();
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
                WithdrawablePreview gone = preview;
                new ClientPreviewBuildPool.Owned(buildPool).close(gone::revoke);
                buildPool = null;
            }
            preview = null;
            borrowed = false;
            changed = true;
        }
        owner = null;
        generation++;
    }

    private void stopBuildPool() {
        if (buildPool != null) {
            buildPool.shutdown(0);
            buildPool = null;
        }
    }

    /** Threads of the preview's build pool running build work now, for the governor. */
    public int buildActive() {
        ClientPreviewBuildPool build = buildPool;
        return build == null ? 0 : build.active();
    }

    private static boolean integrated() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft.isIntegratedServerRunning() && minecraft.getIntegratedServer() != null;
    }
}
