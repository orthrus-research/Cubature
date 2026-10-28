package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.api.util.Box;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.LabRegionOwnership;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.github.opencubicchunks.cubicchunks.core.world.provider.ICubicWorldProvider;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.biome.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.*;
import net.minecraftforge.common.DimensionManager;
import java.nio.channels.FileChannel;
import java.util.*;

/** A synthetic secondary world tests storage lifetime; it makes no claim about Riven multidimension generation. */
final class LabTwoWorlds {
    static final int DIMENSION = 100001;
    static DimensionType type;
    private static final BlockPos MARKER = new BlockPos(-269, 23, 51);
    private WorldServer previous;
    private CubeProviderServer secondary;
    private List<FileChannel> primaryChannels, secondaryChannels;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private int stage, retirements, requestedTick;
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Two-world ownership: " + message);
    }
    boolean tick(LabJob job) throws Exception {
        require(Boolean.getBoolean("cubiclab.storageDiagnostics"), "requires diagnostic preparation");
        if (stage == 0) {
            require(!DimensionManager.isDimensionRegistered(DIMENSION), "fixture dimension already registered");
            type = DimensionType.register("CubicLabStorage", "_cubiclab_storage", DIMENSION, Provider.class, false);
            DimensionManager.registerDimension(DIMENSION, type);
            open();
            Cube cube = secondary.getCubeNow(-17, 1, 3, Requirement.GENERATE);
            require(cube != null, "fixture cube not generated");
            previous.setBlockState(MARKER, Blocks.GOLD_BLOCK.getDefaultState(), 2);
            secondary.getCubeIO().saveColumn(cube.getColumn()); secondary.getCubeIO().saveCube(cube); secondary.getCubeIO().flush();
            job.provider.getCubeIO().flush();
            primaryChannels = LabRegionOwnership.channels(job.provider);
            secondaryChannels = LabRegionOwnership.channels(secondary);
            report.put("primaryOpenChannels", primaryChannels.size()); report.put("secondaryOpenChannels", secondaryChannels.size());
            report.put("dimension", DIMENSION); report.put("marker", Arrays.asList(MARKER.getX(), MARKER.getY(), MARKER.getZ()));
            report.put("height", Arrays.asList(((ICubicWorld) previous).getMinHeight(), ((ICubicWorld) previous).getMaxHeight()));
            request(job); stage = 1; return false;
        }
        if (DimensionManager.getWorld(DIMENSION) != null) return false;
        require(secondaryChannels.stream().noneMatch(FileChannel::isOpen), "retired world's channel remained open");
        require(primaryChannels.stream().allMatch(FileChannel::isOpen), "secondary retirement closed an overworld channel");
        job.provider.getCubeIO().flush();
        Cube control = job.provider.getLoadedCube(job.targets.get(0));
        require(control != null && job.provider.getCubeIO().loadCubeNbt(control.getColumn(), control.getY()).getNbt() != null,
                "overworld storage unavailable after secondary retirement");
        report.put("lastUnloadWaitTicks", job.server.getTickCounter() - requestedTick);
        retirements++;
        if (retirements == 2) {
            DimensionManager.unregisterDimension(DIMENSION);
            report.put("retirements", retirements); report.put("passed", true);
            report.put("scope", "Two live cubic worlds; normal dimension retirement twice, secondary disk reload, exact FileChannel lifetime isolation. No injected unload failure, renderer or Riven secondary generator qualification.");
            return true;
        }
        WorldServer retired = previous; open(); require(previous != retired, "world instance reused");
        Cube loaded = secondary.getCubeNow(-17, 1, 3, Requirement.LOAD);
        require(loaded != null && loaded.getBlockState(MARKER).getBlock() == Blocks.GOLD_BLOCK, "saved marker not reloaded");
        secondary.getCubeIO().flush(); secondaryChannels = LabRegionOwnership.channels(secondary);
        request(job); return false;
    }
    private void open() {
        DimensionManager.keepDimensionLoaded(DIMENSION, true); DimensionManager.initDimension(DIMENSION);
        previous = DimensionManager.getWorld(DIMENSION);
        require(previous != null && ((ICubicWorld) previous).isCubicWorld(), "secondary world is not cubic");
        require(previous.getChunkProvider() instanceof CubeProviderServer, "secondary provider is not cubic");
        secondary = (CubeProviderServer) previous.getChunkProvider();
    }
    private void request(LabJob job) {
        requestedTick = job.server.getTickCounter();
        DimensionManager.keepDimensionLoaded(DIMENSION, false); DimensionManager.unloadWorld(DIMENSION);
    }
    Map<String, Object> summary() { return new LinkedHashMap<>(report); }

    public static final class Provider extends WorldProviderSurface implements ICubicWorldProvider {
        @Override protected void init() { biomeProvider = new BiomeProviderSingle(Biomes.PLAINS); hasSkyLight = true; }
        @Override public DimensionType getDimensionType() { return type; }
        @Override public IChunkGenerator createChunkGenerator() { return new ChunkGeneratorFlat(world, world.getSeed(), false, "3;minecraft:air;1;"); }
        @Override public ICubeGenerator createCubeGenerator() { return new Generator(); }
        @Override public int getOriginalActualHeight() { return 256; }
        @Override public World getWorld() { return world; }
    }
    private static final class Generator implements ICubeGenerator {
        @Override public CubePrimer generateCube(int x, int y, int z) { return new CubePrimer(); }
        @Override public CubePrimer generateCube(int x, int y, int z, CubePrimer primer) { return primer; }
        @Override public void generateColumn(Chunk column) { Arrays.fill(column.getBiomeArray(), (byte) Biome.getIdForBiome(Biomes.PLAINS)); }
        @Override public void populate(ICube cube) { }
        @Override public Box getFullPopulationRequirements(ICube cube) { return NO_REQUIREMENT; }
        @Override public Box getPopulationPregenerationRequirements(ICube cube) { return NO_REQUIREMENT; }
        @Override public void recreateStructures(ICube cube) { }
        @Override public void recreateStructures(Chunk column) { }
        @Override public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) { return Collections.emptyList(); }
        @Override public BlockPos getClosestStructure(String name, BlockPos pos, boolean unexplored) { return null; }
    }
}
