/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: MIT
 *
 * Cubature runtime integration for Demonica 0.9.0 (its Iris shader pipeline).
 * No Demonica code is included; see MODIFICATIONS.md.
 */
package io.github.opencubicchunks.cubicchunks.core.compat.demonica;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

/**
 * Values Demonica's shader uniforms take from a cubic world: the camera's biome, which varies by Y,
 * the world floor, and the camera's depth below its column's surface. Shader packs read
 * {@code bedrockLevel} as the world floor: BSL fades sky, fog, clouds and sun out within 6 blocks of
 * it. Demonica reports 0, the vanilla floor, so everything below Y-6 in a cubic world rendered as if
 * under bedrock.
 */
public final class DemonicaCompat {
    /** The uniform carrying {@link #surfaceDepth()}, a Cubature addition no stock pack reads. */
    public static final String SURFACE_DEPTH_UNIFORM = "cubicSurfaceDepth";

    // Complementary r5.9.3 (Reimagined and Unbound share the file): cave fog grows with the camera's
    // depth below a constant sea level, so a cave above Y61.9 gets none, however deep under a mountain.
    private static final String CAVE_FACTOR_FILE = "/lib/atmospherics/fog/caveFactor.glsl";
    private static final String CAVE_FACTOR_DECLARATION = "float GetCaveFactor() {";
    private static final String CAVE_FACTOR_DEPTH = "clamp(1.0 - cameraPosition.y / oceanAltitude,";

    private static volatile int reported = Integer.MIN_VALUE;

    private DemonicaCompat() {}

    /** The biome at the camera's block in a cubic world, or null to keep Demonica's own lookup. */
    public static Biome cameraBiome() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (mc.world == null || camera == null || !((ICubicWorld) mc.world).isCubicWorld()) {
            return null;
        }
        return mc.world.getBiome(new BlockPos(camera.posX, camera.posY + camera.getEyeHeight(), camera.posZ));
    }

    /** The world floor for cubic worlds; Demonica's own value for any other world. */
    public static IntSupplier bedrockLevel(IntSupplier vanilla) {
        return () -> {
            World world = Minecraft.getMinecraft().world;
            if (world == null || !((ICubicWorld) world).isCubicWorld()) {
                return vanilla.getAsInt();
            }
            int floor = ((ICubicWorld) world).getMinHeight();
            if (floor != reported) {
                reported = floor;
                CubicChunks.LOGGER.info("Shader bedrockLevel is Y{} (cubic world floor)", floor);
            }
            return floor;
        };
    }

    /**
     * How far the camera's eye is below the top of its column's highest light-blocking block (the
     * height map the server sends with each column), 0 at or above it and outside a cubic world.
     */
    public static double surfaceDepth() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (mc.world == null || camera == null || !((ICubicWorld) mc.world).isCubicWorld()) {
            return 0;
        }
        int x = MathHelper.floor(camera.posX), z = MathHelper.floor(camera.posZ);
        Chunk column = mc.world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
        if (column == null) {
            return 0;
        }
        int top = ((IColumn) column).getOpacityIndex().getTopBlockY(x & 15, z & 15);
        return top == Coords.NO_HEIGHT ? 0 : Math.max(0, top + 1 - (camera.posY + camera.getEyeHeight()));
    }

    /**
     * Registers {@link #SURFACE_DEPTH_UNIFORM} with one of Demonica's uniform holders. Cubature does
     * not compile against Demonica, so the holder's uniform1f(frequency, name, DoubleSupplier) is
     * found by name.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerSurfaceDepth(Object uniforms) {
        try {
            ClassLoader loader = uniforms.getClass().getClassLoader();
            Class<?> holder = Class.forName("net.coderbot.iris.gl.uniform.UniformHolder", false, loader);
            Class frequency = Class.forName("net.coderbot.iris.gl.uniform.UniformUpdateFrequency", false, loader);
            Method uniform1f = holder.getMethod("uniform1f", frequency, String.class, DoubleSupplier.class);
            DoubleSupplier depth = DemonicaCompat::surfaceDepth;
            uniform1f.invoke(uniforms, Enum.valueOf(frequency, "PER_FRAME"), SURFACE_DEPTH_UNIFORM, depth);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CubicChunks.LOGGER.warn("Could not register the {} shader uniform", SURFACE_DEPTH_UNIFORM, e);
        }
    }

    /**
     * A shader pack file's text as Demonica should compile it, or null to keep it. With
     * {@link CubicChunksConfig#complementaryCaveFog} on, Complementary's cave fog follows
     * {@link #SURFACE_DEPTH_UNIFORM} instead of the camera's depth below Y61.9. Both edits stay on
     * their lines, so the pack's line numbers in compile errors are unchanged; any other text is
     * left alone.
     */
    public static String patchPackSource(Path path, String source) {
        // Demonica resolves the pack-relative path against a folder or a zip root, which may itself be relative.
        if (!CubicChunksConfig.complementaryCaveFog
                || !("/" + path.toString().replace('\\', '/')).endsWith(CAVE_FACTOR_FILE)) {
            return null;
        }
        if (occurrences(source, CAVE_FACTOR_DECLARATION) != 1 || occurrences(source, CAVE_FACTOR_DEPTH) != 1) {
            CubicChunks.LOGGER.warn("complementaryCaveFog: {} is not Complementary r5.9.3's; left unchanged", path);
            return null;
        }
        CubicChunks.LOGGER.info("complementaryCaveFog: Complementary's cave fog follows {} ({})",
                SURFACE_DEPTH_UNIFORM, path);
        return source
                .replace(CAVE_FACTOR_DECLARATION, "uniform float " + SURFACE_DEPTH_UNIFORM + "; " + CAVE_FACTOR_DECLARATION)
                .replace(CAVE_FACTOR_DEPTH, "clamp(" + SURFACE_DEPTH_UNIFORM + " / oceanAltitude,");
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }
}
