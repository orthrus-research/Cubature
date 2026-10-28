package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed full-height lighting work. Timed calls include population, first light and surface tracking. */
final class LabFirstLight {
    private int completed;
    private long drainNanos;

    void prepare(LabJob job) {
        for (CubePos pos : job.targets) {
            Cube cube = job.provider.getLoadedCube(pos);
            if (cube == null || cube.isInitialLightingDone())
                throw new IllegalStateException("First-light fixture must be generated, remote and initially unlit");
            int x = pos.getX() * 16, y = pos.getY() * 16, z = pos.getZ() * 16;
            cube.setBlockState(new BlockPos(x + 1, y + 7, z + 1), Blocks.GLOWSTONE.getDefaultState());
            cube.setBlockState(new BlockPos(x + 2, y + 15, z + 2), Blocks.STONE.getDefaultState());
            cube.setBlockState(new BlockPos(x + 3, y + 15, z + 3), Blocks.AIR.getDefaultState());
            cube.markDirty();
        }
    }

    boolean tick(LabJob job) {
        for (int i = 0; i < 8 && job.progress < job.targets.size(); i++) {
            CubePos pos = job.targets.get(job.progress++);
            long start = System.nanoTime();
            Cube cube = job.provider.getCubeNow(pos.getX(), pos.getY(), pos.getZ(), Requirement.LIGHT);
            job.operations.add(System.nanoTime() - start);
            if (cube == null || !cube.getCoords().equals(pos) || !cube.isFullyPopulated()
                    || !cube.isInitialLightingDone() || !cube.isSurfaceTracked())
                throw new IllegalStateException("Lighting fixture did not reach LIGHT at " + pos);
            completed++; job.operationCount++;
        }
        if (completed != job.targets.size()) return false;
        long start = System.nanoTime();
        ((ICubicWorldInternal) job.world).getLightingManager().processUpdates();
        drainNanos = System.nanoTime() - start;
        return true;
    }

    Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("litCubes", completed); result.put("geometryEditsPerCube", 3);
        result.put("lightingDrainNanos", drainNanos);
        result.put("scope", "Fixed full-height workload cohorts; litCubes records the actual count; LIGHT calls include population and surface tracking; final drain is lighting, not disk flush. Save and independent --lighting inspection follow separately.");
        return result;
    }
}
