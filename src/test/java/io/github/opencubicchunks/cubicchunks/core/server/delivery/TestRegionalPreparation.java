package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.util.Box;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class TestRegionalPreparation {
    private static final int REGION = 2048, REACH = 4096, NEAR = 192;
    private static final RegionalPreparation.Bounds ANYWHERE = (minX, minZ, maxX, maxZ) -> true;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test public void aStandingPlayersRegionsGoNearestFirst() {
        RecordingGenerator generator = new RecordingGenerator();
        RegionalPreparation preparation = new RegionalPreparation(generator, Runnable::run, 4);
        long now = SECOND;
        for (int second = 0; second < 40; second++, now += SECOND) {
            preparation.plan(now, players(1024, 1024), REACH, NEAR, ANYWHERE, .40, .5);
            preparation.pump(now, false);
        }
        List<Double> distances = new ArrayList<>();
        for (long key : generator.prepared) distances.add(distance(key, 1024, 1024));
        assertEquals("Its own region first", key(0, 0), (long) generator.prepared.get(0));
        List<Double> sorted = new ArrayList<>(distances);
        Collections.sort(sorted);
        assertEquals(sorted, distances);
        assertEquals("Every region within reach, each once",
                new HashSet<>(generator.prepared).size(), generator.prepared.size());
    }

    @Test public void aMovingPlayersRegionsAheadGoFirst() {
        RecordingGenerator generator = new RecordingGenerator();
        RegionalPreparation preparation = new RegionalPreparation(generator, Runnable::run, 1);
        // Flying east at 16 blocks a second from the middle of region (0, 0).
        preparation.plan(SECOND, players(1024, 1024), REACH, NEAR, ANYWHERE, .40, .5);
        preparation.plan(2 * SECOND, players(1040, 1024), REACH, NEAR, ANYWHERE, .40, .5);
        long now = 2 * SECOND;
        for (int i = 0; i < 6; i++, now += SECOND) preparation.pump(now, false);
        int east = generator.prepared.indexOf(key(1, 0)), west = generator.prepared.indexOf(key(-1, 0));
        assertTrue("East prepared", east >= 0);
        assertTrue("East before west", west < 0 || east < west);
    }

    @Test public void nearRegionsStartWhateverThePermits() {
        RecordingGenerator generator = new RecordingGenerator();
        RegionalPreparation preparation = new RegionalPreparation(generator, Runnable::run, 4);
        // Saturated: the governor holds every region beyond the view, past one trickle.
        preparation.plan(SECOND, players(2048, 2048), REACH, NEAR, ANYWHERE, .99, 10);
        assertEquals(0, preparation.permits());
        preparation.pump(SECOND, false);
        // The player stands on a corner of four regions, all within the view.
        Set<Long> near = new HashSet<>(List.of(key(0, 0), key(1, 0), key(0, 1), key(1, 1)));
        assertTrue(generator.prepared.containsAll(near));
        assertTrue("At most one region beyond the view trickles", generator.prepared.size() <= 5);
    }

    @Test public void aDeclineHoldsRegionsBeyondTheViewForThirtySeconds() {
        RecordingGenerator generator = new RecordingGenerator();
        generator.decline = true;
        RegionalPreparation preparation = new RegionalPreparation(generator, Runnable::run, 4);
        long now = SECOND;
        preparation.plan(now, players(1024, 1024), REACH, NEAR, ANYWHERE, .40, .5);
        preparation.pump(now, false);
        int attempts = generator.prepared.size();
        assertTrue(attempts >= 1);
        // Declined regions are not done; a pause now holds the regions beyond the view.
        generator.decline = false;
        preparation.pump(now + SECOND, false);
        long beyond = generator.prepared.stream().skip(attempts).filter(k -> distance(k, 1024, 1024) > NEAR).count();
        assertEquals("Held within the pause", 0, beyond);
        preparation.pump(now + RegionalPreparation.DECLINED_PAUSE + SECOND, false);
        beyond = generator.prepared.stream().skip(attempts).filter(k -> distance(k, 1024, 1024) > NEAR).count();
        assertTrue("Resumed after the pause", beyond > 0);
    }

    @Test public void noRegionsWhenTheGeneratorPreparesNone() {
        RecordingGenerator generator = new RecordingGenerator();
        generator.blocks = 0;
        RegionalPreparation preparation = new RegionalPreparation(generator, Runnable::run, 4);
        preparation.plan(SECOND, players(0, 0), REACH, NEAR, ANYWHERE, .40, .5);
        preparation.pump(SECOND, false);
        assertTrue(generator.prepared.isEmpty());
    }

    @Test public void nothingStartsWhileTheNativeViewIsBehind() {
        RecordingGenerator generator = new RecordingGenerator();
        RegionalPreparation preparation = new RegionalPreparation(generator, Runnable::run, 4);
        preparation.plan(SECOND, players(1024, 1024), REACH, NEAR, ANYWHERE, .40, .5);
        // A join: the native view is far behind, so even the player's own region waits.
        preparation.pump(SECOND, true);
        assertTrue(generator.prepared.isEmpty());
        preparation.pump(2 * SECOND, false);
        assertEquals(key(0, 0), (long) generator.prepared.get(0));
    }

    private static List<RegionalPreparation.Position> players(double x, double z) {
        return List.of(new RegionalPreparation.Position("player", x, z));
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) ^ (rz & 0xffffffffL);
    }

    private static double distance(long key, double x, double z) {
        double minX = (double) (int) (key >> 32) * REGION, minZ = (double) (int) key * REGION;
        double dx = Math.max(0, Math.max(minX - x, x - minX - REGION));
        double dz = Math.max(0, Math.max(minZ - z, z - minZ - REGION));
        return Math.hypot(dx, dz);
    }

    private static final class RecordingGenerator implements ICubeGenerator {
        final List<Long> prepared = new ArrayList<>();
        int blocks = REGION;
        boolean decline;

        @Override public int preparationRegionBlocks() { return blocks; }
        @Override public boolean prepareRegion(int regionX, int regionZ) {
            prepared.add(key(regionX, regionZ));
            return !decline;
        }

        @Override public CubePrimer generateCube(int cubeX, int cubeY, int cubeZ) { throw new AssertionError("not used"); }
        @Override public void generateColumn(Chunk column) { throw new AssertionError("not used"); }
        @Override public void populate(ICube cube) { throw new AssertionError("not used"); }
        @Override public Box getFullPopulationRequirements(ICube cube) { throw new AssertionError("not used"); }
        @Override public Box getPopulationPregenerationRequirements(ICube cube) { throw new AssertionError("not used"); }
        @Override public void recreateStructures(ICube cube) { }
        @Override public void recreateStructures(Chunk column) { }
        @Override public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) { throw new AssertionError("not used"); }
        @Override public BlockPos getClosestStructure(String name, BlockPos pos, boolean findUnexplored) { throw new AssertionError("not used"); }
    }
}
