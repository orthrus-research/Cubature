package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.Gson;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;
import io.github.opencubicchunks.cubicchunks.core.server.ChunkGc;
import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import io.github.opencubicchunks.cubicchunks.core.server.ICubicPlayerList;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

/** Generates ordinary lit cubes in a fresh owned world; never evaluates terrain independently. */
final class LabPregenerator {
  final MinecraftServer server;
  final WorldServer world;
  final CubeProviderServer provider;
  final String id, inputs;
  final int originX, originZ, width, height, minY, maxY, limit, initialPosition;
  final int batchColumns, tickBudgetMillis;
  final LabPregenJournal journal;
  final List<Map<String, Object>> rows = new ArrayList<>();
  int position;
  boolean done;
  long batchStart = System.nanoTime(), generationNanos;

  LabPregenerator(MinecraftServer server, String id, int x, int z, int width, int height, int limit)
      throws IOException {
    if (!id.matches("[A-Za-z0-9_-]{1,64}")
        || width < 1
        || height < 1
        || width > 1250
        || height > 1250
        || limit < 1
        || Math.min(x, (long) x + width - 1) * 16 < -29_000_000L
        || Math.max(x, (long) x + width) * 16 > 29_000_000L
        || Math.min(z, (long) z + height - 1) * 16 < -29_000_000L
        || Math.max(z, (long) z + height) * 16 > 29_000_000L) {
      throw new IllegalArgumentException("Invalid pregeneration extent or session limit");
    }
    this.server = server;
    this.id = id;
    originX = x;
    originZ = z;
    this.width = width;
    this.height = height;
    this.limit = limit;
    inputs = System.getProperty("cubiclab.pregenInputs", "");
    if (!inputs.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Pregeneration requires frozen input fingerprint");
    }
    batchColumns = integerProperty("cubiclab.pregenBatchColumns", 128, 1, 256);
    tickBudgetMillis = integerProperty("cubiclab.pregenTickMillis", 1000, 50, 2000);
    world = server.getWorld(0);
    if (!((ICubicWorld) world).isCubicWorld()
        || !world.getWorldInfo().getTerrainType().getName().equals("riven")) {
      throw new IllegalArgumentException("Pregeneration requires the native Riven cubic world");
    }
    if (server.getPlayerList().getViewDistance() != 12
        || ((ICubicPlayerList) server.getPlayerList()).getVerticalViewDistance() != 12) {
      throw new IllegalArgumentException("Pregeneration requires effective server 12/12");
    }
    requireNoPlayers();
    provider = (CubeProviderServer) world.getChunkProvider();
    // The retained map covers the lab band; the world may extend below and above it.
    int worldMinY = ((ICubicWorld) world).getMinHeight();
    int worldMaxY = ((ICubicWorld) world).getMaxHeight();
    if (worldMinY > LabJob.BAND_MIN_Y || worldMaxY < LabJob.BAND_MAX_Y) {
      throw new IllegalArgumentException("World height does not contain the lab band");
    }
    minY = LabJob.BAND_MIN_Y;
    maxY = LabJob.BAND_MAX_Y;
    Path output = server.getFile("cubiclab-pregeneration").toPath().resolve(id);
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("schema", 1);
    definition.put("operation", "native-light-save-unload");
    definition.put("id", id);
    definition.put("originChunkX", x);
    definition.put("originChunkZ", z);
    definition.put("widthColumns", width);
    definition.put("heightColumns", height);
    definition.put("seed", world.getSeed());
    definition.put("generatorOptions", world.getWorldInfo().getGeneratorOptions());
    definition.put("minY", minY);
    definition.put("maxYExclusive", maxY);
    definition.put("inputsSha256", inputs);
    journal =
        new LabPregenJournal(
            output, new Gson().toJsonTree(definition).getAsJsonObject(), width * height);
    position = journal.committed();
    initialPosition = position;
    Map<String, Object> session = new LinkedHashMap<>();
    session.put("runtime", LabData.runtime());
    session.put("startUtc", Instant.now().toString());
    session.put("startColumn", position);
    session.put("sessionLimit", limit);
    session.put("batchColumns", batchColumns);
    session.put("tickBudgetMillis", tickBudgetMillis);
    session.put("horizontalViewDistance", 12);
    session.put("verticalViewDistance", 12);
    session.put(
        "scope",
        "Native LIGHT requirement, all allocated cubes saved and writer-flushed before each"
            + " checkpoint. Neighbor halos may extend beyond the target. No player-view or"
            + " whole-map DH coverage claim.");
    LabData.write(output.resolve("session-" + UUID.randomUUID() + ".json"), session);
  }

  private static int integerProperty(String name, int fallback, int min, int max) {
    int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
    if (value < min || value > max) throw new IllegalArgumentException("Invalid " + name);
    return value;
  }

  private void requireNoPlayers() {
    if (!server.getPlayerList().getPlayers().isEmpty()) {
      throw new IllegalStateException("Pregeneration requires an owned server with no players");
    }
  }

  void tick() throws IOException {
    requireNoPlayers();
    long deadline = System.nanoTime() + tickBudgetMillis * 1_000_000L;
    do {
      if (position >= width * height || position - initialPosition >= limit) {
        commit();
        done = true;
        message(
            "CUBICLAB_PREGEN_RESULT id="
                + id
                + " PASS committed="
                + journal.committed()
                + " total="
                + width * height
                + " complete="
                + (position == width * height));
        return;
      }
      if (world.getSaveHandler().getWorldDirectory().getUsableSpace() < 30L * 1024 * 1024 * 1024) {
        throw new IllegalStateException("Pregeneration stopped with less than 30 GiB disk reserve");
      }
      int x = originX + position % width, z = originZ + position / width;
      long started = System.nanoTime();
      for (int cy = minY >> 4; cy < maxY >> 4; cy++) {
        Cube cube = provider.getCubeNow(x, cy, z, Requirement.LIGHT);
        if (cube == null
            || cube.getX() != x
            || cube.getY() != cy
            || cube.getZ() != z
            || !cube.isFullyPopulated()
            || !cube.isInitialLightingDone()
            || !cube.isSurfaceTracked()) {
          throw new IllegalStateException(
              "Native cube did not reach LIGHT at " + x + "," + cy + "," + z);
        }
        cube.markDirty();
      }
      generationNanos += System.nanoTime() - started;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("chunkX", x);
      row.put("chunkZ", z);
      row.put("cubes", (maxY - minY) >> 4);
      rows.add(row);
      position++;
      if (rows.size() >= batchColumns) commit();
    } while (System.nanoTime() < deadline);
  }

  private void commit() throws IOException {
    if (rows.isEmpty()) return;
    long started = System.nanoTime();
    provider.saveChunks(true);
    new ChunkGc(provider).chunkGc();
    provider.getCubeIO().flush();
    long saveNanos = System.nanoTime() - started;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("firstColumn", journal.committed());
    data.put("endColumn", position);
    data.put("inputsSha256", inputs);
    data.put("elapsedNanos", System.nanoTime() - batchStart);
    data.put("generationNanos", generationNanos);
    data.put("saveNanos", saveNanos);
    data.put("loadedCubesAfterGc", provider.getLoadedCubeCount());
    data.put(
        "heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    data.put("rows", rows);
    journal.append(data);
    message(
        "CUBICLAB_PREGEN_PROGRESS id="
            + id
            + " committed="
            + journal.committed()
            + " total="
            + width * height
            + " batchMs="
            + (System.nanoTime() - batchStart) / 1_000_000
            + " loaded="
            + provider.getLoadedCubeCount());
    rows.clear();
    generationNanos = 0;
    batchStart = System.nanoTime();
    if (provider.getLoadedCubeCount() > 32768) {
      throw new IllegalStateException("Pregeneration residency exceeded 32768 cubes after unload");
    }
  }

  void cancel() throws IOException {
    commit();
    done = true;
  }

  void message(String value) {
    server.sendMessage(new TextComponentString(value));
  }
}
