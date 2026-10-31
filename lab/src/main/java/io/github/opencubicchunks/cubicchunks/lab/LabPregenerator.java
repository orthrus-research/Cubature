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

/**
 * Generates ordinary lit cubes in a fresh owned world; never evaluates terrain independently. Optionally
 * ({@code cubiclab.pregenFullHeight}) every cube from the world's bottom to its top rather than the lab band;
 * optionally ({@code cubiclab.pregenDhFeed}) each column, once all its cubes are lit and population can no longer
 * reach it, handed to Distant Horizons' server level, waiting while DH's update backlog is long and draining it before
 * the result; and ({@code cubiclab.pregenAhead}) the next columns' cubes scheduled on Cubature's generation workers
 * while this thread lights the current one. Scheduling changes timing only.
 */
final class LabPregenerator {
  final MinecraftServer server;
  final WorldServer world;
  final CubeProviderServer provider;
  final String id, inputs;
  final int originX, originZ, width, height, minY, maxY, limit, initialPosition;
  final int batchColumns, tickBudgetMillis, aheadColumns, dhBacklog, flushBatches;
  final List<Map<String, Object>> unflushed = new ArrayList<>();
  int nextFirst;
  final boolean dhFeed;
  long dhFed, dhWaits, drainSince;
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
    aheadColumns = integerProperty("cubiclab.pregenAhead", 0, 0, 64);
    flushBatches = integerProperty("cubiclab.pregenFlushBatches", 1, 1, 16);
    dhBacklog = integerProperty("cubiclab.pregenDhBacklog", 2048, 64, 65536);
    dhFeed = Boolean.parseBoolean(System.getProperty("cubiclab.pregenDhFeed", "false"));
    boolean fullHeight = Boolean.parseBoolean(System.getProperty("cubiclab.pregenFullHeight", "false"));
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
    minY = fullHeight ? worldMinY : LabJob.BAND_MIN_Y;
    maxY = fullHeight ? worldMaxY : LabJob.BAND_MAX_Y;
    if (dhFeed && !io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat.hasServerIntegration()) {
      throw new IllegalArgumentException("Pregeneration with the DH feed requires Distant Horizons on the server");
    }
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
    if (dhFeed) definition.put("distantHorizonsFeed", "each lit column handed to DH's server level, backlog drained");
    definition.put("inputsSha256", inputs);
    journal =
        new LabPregenJournal(
            output, new Gson().toJsonTree(definition).getAsJsonObject(), width * height);
    position = journal.committed();
    initialPosition = position;
    nextFirst = position;
    Map<String, Object> session = new LinkedHashMap<>();
    session.put("runtime", LabData.runtime());
    session.put("startUtc", Instant.now().toString());
    session.put("startColumn", position);
    session.put("sessionLimit", limit);
    session.put("batchColumns", batchColumns);
    session.put("tickBudgetMillis", tickBudgetMillis);
    session.put("aheadColumns", aheadColumns);
    session.put("flushBatches", flushBatches);
    session.put("dhBacklogLimit", dhBacklog);
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
        commit(true);
        if (dhFeed && dhPending() > 0 && (drainSince == 0 || System.nanoTime() - drainSince < 300_000_000_000L)) {
          if (drainSince == 0) drainSince = System.nanoTime();
          dhWaits++;
          return; // DH turns the last columns into LOD data before the result, for up to 5 minutes
        }
        done = true;
        message(
            "CUBICLAB_PREGEN_RESULT id="
                + id
                + " PASS committed="
                + journal.committed()
                + " total="
                + width * height
                + " complete="
                + (position == width * height)
                + (dhFeed ? " dhFed=" + dhFed + " dhPending=" + dhPending() : ""));
        return;
      }
      if (dhFeed && dhPending() > dhBacklog) {
        dhWaits++;
        return; // DH works off-thread; the next tick continues
      }
      if (aheadColumns > 0) schedule();
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
        if (dhFeed && cy == (maxY >> 4) - 1) {
          if (!io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat.feedServerColumnNow(
              (net.minecraft.world.chunk.Chunk) cube.getColumn())) {
            throw new IllegalStateException("Distant Horizons refused the column at " + x + "," + z);
          }
          dhFed++;
        }
      }
      generationNanos += System.nanoTime() - started;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("chunkX", x);
      row.put("chunkZ", z);
      row.put("cubes", (maxY - minY) >> 4);
      rows.add(row);
      position++;
      if (rows.size() >= batchColumns) commit(false);
    } while (System.nanoTime() < deadline);
  }

  private static int dhPending() {
    return io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantHorizonsCompat.pendingServerUpdates();
  }

  /**
   * The next columns' cubes asked of the generation workers, before every column: a column not yet loaded is first
   * prepared, and its cubes can be scheduled only on a later request; requests in flight just poll.
   */
  private void schedule() {
    int end = Math.min(width * height, position + 1 + aheadColumns);
    for (int p = position + 1; p < end; p++) {
      int x = originX + p % width, z = originZ + p / width;
      for (int cy = minY >> 4; cy < maxY >> 4; cy++) provider.getCubeForDelivery(x, cy, z, Requirement.LIGHT);
    }
  }

  /**
   * Saves and unloads the batch; every {@code flushBatches} batches (and at the end) waits for the storage writer and
   * records the batches it has written, so the writer works while later columns generate. A record is published only
   * after its cubes are written.
   */
  private void commit(boolean flush) throws IOException {
    if (!rows.isEmpty()) {
      long started = System.nanoTime();
      provider.saveChunks(true);
      new ChunkGc(provider).chunkGc();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("firstColumn", nextFirst);
      data.put("endColumn", position);
      data.put("inputsSha256", inputs);
      data.put("elapsedNanos", System.nanoTime() - batchStart);
      data.put("generationNanos", generationNanos);
      data.put("saveNanos", System.nanoTime() - started);
      data.put("loadedCubesAfterGc", provider.getLoadedCubeCount());
      if (dhFeed) {
        data.put("dhFed", dhFed);
        data.put("dhPending", dhPending());
        data.put("dhWaitTicks", dhWaits);
      }
      data.put(
          "heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
      data.put("rows", new ArrayList<>(rows));
      unflushed.add(data);
      nextFirst = position;
      rows.clear();
      generationNanos = 0;
      batchStart = System.nanoTime();
      if (provider.getLoadedCubeCount() > 32768) {
        throw new IllegalStateException("Pregeneration residency exceeded 32768 cubes after unload");
      }
    }
    if (unflushed.isEmpty() || (!flush && unflushed.size() < flushBatches)) return;
    long started = System.nanoTime();
    provider.getCubeIO().flush();
    long flushNanos = System.nanoTime() - started;
    Map<String, Object> last = unflushed.get(unflushed.size() - 1);
    last.put("flushNanos", flushNanos);
    last.put("saveNanos", (Long) last.get("saveNanos") + flushNanos);
    last.put("elapsedNanos", (Long) last.get("elapsedNanos") + flushNanos);
    for (Map<String, Object> data : unflushed) journal.append(data);
    unflushed.clear();
    message(
        "CUBICLAB_PREGEN_PROGRESS id="
            + id
            + " committed="
            + journal.committed()
            + " total="
            + width * height
            + " flushMs="
            + flushNanos / 1_000_000
            + " loaded="
            + provider.getLoadedCubeCount());
    batchStart = System.nanoTime();
  }

  void cancel() throws IOException {
    commit(true);
    done = true;
  }

  void message(String value) {
    server.sendMessage(new TextComponentString(value));
  }
}
