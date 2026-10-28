package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Arrays;

/** Optional owner-thread diagnostic of the actual cube writer; no save is queued during the perturbation. */
public final class LabLightingSnapshotProbe {
    private LabLightingSnapshotProbe() { }

    public static void verify(Cube cube) {
        int[] live = cube.getColumn().getHeightMap();
        int[] expected = live.clone();
        NBTTagCompound snapshot = IONbtWriter.write(cube).getCompoundTag("Level").getCompoundTag("LightingInfo");
        int[] saved = snapshot.getIntArray("LastHeightMap");
        if (!Arrays.equals(expected, saved)) throw new IllegalStateException("Invalid initial lighting heightmap snapshot");
        if (saved == live) throw new IllegalStateException("Lighting snapshot aliases the live column heightmap");
        int previous = live[0];
        try {
            live[0] ^= 1;
            if (!Arrays.equals(expected, saved)) throw new IllegalStateException("Lighting snapshot changed with the column heightmap");
        } finally { live[0] = previous; }
    }
}
