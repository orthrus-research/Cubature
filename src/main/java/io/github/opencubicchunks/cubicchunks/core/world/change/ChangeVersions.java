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
package io.github.opencubicchunks.cubicchunks.core.world.change;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

/**
 * The server's edit-version counter, one for every dimension, kept in the save's shared data. Versions only grow.
 * Versions handed out after the last save are lost in a crash, so loading moves the counter on by {@link #LOAD_STEP}:
 * a version handed out before a crash is not handed out again, as long as fewer than that many were handed out
 * between two saves.
 */
public class ChangeVersions extends WorldSavedData {

    public static final String NAME = "cubicchunks_change_versions";
    public static final long LOAD_STEP = 1L << 20;

    private long next = 1;

    public ChangeVersions(String name) {
        super(name);
    }

    /** The counter of the world's save, loaded or made on first use. */
    public static ChangeVersions of(World world) {
        MapStorage storage = world.getMapStorage();
        ChangeVersions versions = (ChangeVersions) storage.getOrLoadData(ChangeVersions.class, NAME);
        if (versions == null) {
            versions = new ChangeVersions(NAME);
            storage.setData(NAME, versions);
        }
        return versions;
    }

    /** The next version, larger than any handed out before. */
    public long next() {
        long version = next++;
        markDirty();
        return version;
    }

    /** The version {@link #next()} will hand out. */
    public long peek() {
        return next;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        next = Math.max(1, nbt.getLong("next")) + LOAD_STEP;
        markDirty();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        compound.setLong("next", next);
        return compound;
    }
}
