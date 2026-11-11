/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.worldgen.populator.ICubicPopulator;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

public class CubeGeneratorsRegistry {

    /** List of populators added by other mods to vanilla compatibility generator type */
    private static final List<ICubicPopulator> customPopulatorsForFlatCubicGenerator = new ArrayList<ICubicPopulator>();
    private static final List<BiConsumer<? super World, ? super LoadingData<CubePos>>> cubeLoadingCallbacks = new ArrayList<>(2);
    private static final List<BiConsumer<? super World, ? super LoadingData<ChunkPos>>> columnLoadingCallbacks = new ArrayList<>(2);

    private static final Collection<BiConsumer<? super World, ? super LoadingData<CubePos>>> cubeLoadingCallbacksView = Collections.unmodifiableCollection(cubeLoadingCallbacks);
    private static final Collection<BiConsumer<? super World, ? super LoadingData<ChunkPos>>> columnLoadingCallbacksView = Collections.unmodifiableCollection(columnLoadingCallbacks);

    /**
     * Populators added here will be launched prior to any other. It is
     * recommended to use this function in init or pre init event of a mod.
     *
     * @param populator populator instance to register
     */
    public static void registerForCompatibilityGenerator(ICubicPopulator populator) {
        if (!customPopulatorsForFlatCubicGenerator.contains(populator))
            customPopulatorsForFlatCubicGenerator.add(populator);
    }

    public static void populateVanillaCubic(World world, Random rand, ICube cube) {
        for (ICubicPopulator populator : customPopulatorsForFlatCubicGenerator) {
            populator.generate(world, rand, cube.getCoords(), cube.getBiome(cube.getCoords().getCenterBlockPos()));
        }
    }

    /**
     * Registers a callback invoked after loading cube NBT from disk. This callback will get called even if no data is found, potentially allowing
     * to prepare data for world generation asynchronously in a cache.
     *
     * @param cubeCallback the callback to be registered
     */
    public static void registerCubeAsyncLoadingCallback(BiConsumer<? super World, ? super LoadingData<CubePos>> cubeCallback) {
        cubeLoadingCallbacks.add(cubeCallback);
    }

    /**
     * Registers a callback invoked after loading column NBT from disk. This callback will get called even if no data is found, potentially allowing
     * to prepare data for world generation asynchronously in a cache.
     *
     * @param columnCallback the callback to be registered
     */
    public static void registerColumnAsyncLoadingCallback(BiConsumer<? super World, ? super LoadingData<ChunkPos>> columnCallback) {
        columnLoadingCallbacks.add(columnCallback);
    }

    public static Collection<BiConsumer<? super World, ? super LoadingData<CubePos>>> getCubeAsyncLoadingCallbacks() {
        return cubeLoadingCallbacksView;
    }

    public static Collection<BiConsumer<? super World, ? super LoadingData<ChunkPos>>> getColumnAsyncLoadingCallbacks() {
        return columnLoadingCallbacksView;
    }
}
