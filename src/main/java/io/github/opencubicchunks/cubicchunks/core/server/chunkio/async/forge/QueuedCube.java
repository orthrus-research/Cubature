/*
 * Minecraft Forge
 * Copyright (c) 2016.
 * Cubature changes by Orthrus Research, last implementation change 2026-10-14; see MODIFICATIONS.md.
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge;

import com.google.common.base.MoreObjects;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Taking from Sponge, with modifications
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
class QueuedCube {

    final int x;
    final int y;
    final int z;
    @Nonnull final World world;

    QueuedCube(int x, int y, int z, World world) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.world = world;
    }

    @Override
    public int hashCode() {
        return hashCoordinates(x, y, z) ^ world.hashCode();
    }

    static int hashCoordinates(int x, int y, int z) {
        // Mix coordinates independently so axis-aligned loads do not share one hash.
        int hash = x * 0x9E3779B9;
        hash = (Integer.rotateLeft(hash, 13) ^ y) * 0x9E3779B9;
        return (Integer.rotateLeft(hash, 13) ^ z) * 0x9E3779B9;
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object == this) return true;
        if (!(object instanceof QueuedCube)) return false;
        QueuedCube other = (QueuedCube) object;
        return x == other.x && y == other.y && z == other.z && world == other.world;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .addValue(this.world)
                .add("x", this.x)
                .add("y", this.y)
                .add("z", this.z)
                .toString();
    }
}
