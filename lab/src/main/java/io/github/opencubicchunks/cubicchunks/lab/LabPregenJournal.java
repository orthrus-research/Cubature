package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A bounded native pregeneration journal. The caller flushes world saves before append. */
final class LabPregenJournal {
  private static final Gson GSON = new Gson();
  final Path output;
  final int total;
  private final JsonObject definition;
  private int committed;

  LabPregenJournal(Path output, JsonObject definition, int total) throws IOException {
    this.output = output;
    this.definition = new JsonParser().parse(definition.toString()).getAsJsonObject();
    this.total = total;
    Files.createDirectories(output);
    Path path = output.resolve("definition.json");
    if (Files.exists(path)) {
      if (!read(path).equals(definition)) {
        throw new IllegalArgumentException("Pregeneration definition changed; use a fresh world");
      }
    } else {
      atomicJson(path, definition);
    }
    Path checkpoint = output.resolve("checkpoint.json");
    if (Files.exists(checkpoint)) {
      JsonObject status = read(checkpoint);
      committed = status.get("committedColumns").getAsInt();
      if (committed < 0 || committed > total || status.get("totalColumns").getAsInt() != total) {
        throw new IOException("Invalid pregeneration checkpoint");
      }
      if (committed > 0) {
        int first = status.get("lastBatchFirstColumn").getAsInt();
        if (validateBatch(first) != committed) {
          throw new IOException("Checkpoint disagrees with its saved batch");
        }
      }
    }
    // A batch is published only after the native save/GC/writer flush. If a process
    // stops before publishing its checkpoint, recover that complete receipt exactly.
    while (committed < total && Files.exists(batchPath(committed))) {
      int first = committed;
      committed = validateBatch(first);
      checkpoint(first);
    }
  }

  int committed() {
    return committed;
  }

  private Path batchPath(int first) {
    return output.resolve(String.format(Locale.ROOT, "batch-%07d.json.gz", first));
  }

  private int validateBatch(int first) throws IOException {
    JsonObject batch;
    try (Reader reader =
        new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(batchPath(first))), StandardCharsets.UTF_8)) {
      batch = new JsonParser().parse(reader).getAsJsonObject();
    }
    return validateData(first, batch);
  }

  private int validateData(int first, JsonObject batch) throws IOException {
    int end = batch.get("endColumn").getAsInt();
    if (first < 0
        || batch.get("firstColumn").getAsInt() != first
        || end <= first
        || end > total
        || end - first > 256
        || batch.getAsJsonArray("rows").size() != end - first
        || !definition.get("inputsSha256").equals(batch.get("inputsSha256"))) {
      throw new IOException("Invalid pregeneration batch " + first);
    }
    int width = definition.get("widthColumns").getAsInt();
    int x = definition.get("originChunkX").getAsInt();
    int z = definition.get("originChunkZ").getAsInt();
    for (int i = first; i < end; i++) {
      JsonObject row = batch.getAsJsonArray("rows").get(i - first).getAsJsonObject();
      if (row.get("chunkX").getAsInt() != x + i % width
          || row.get("chunkZ").getAsInt() != z + i / width
          || row.get("cubes").getAsInt() != 32) {
        throw new IOException("Pregeneration batch coordinates disagree with definition");
      }
    }
    return end;
  }

  void append(Map<String, Object> data) throws IOException {
    JsonObject value = GSON.toJsonTree(data).getAsJsonObject();
    if (value.get("firstColumn").getAsInt() != committed) {
      throw new IOException("Noncontiguous pregeneration batch");
    }
    validateData(committed, value);
    Path path = batchPath(committed);
    if (Files.exists(path)) {
      throw new IOException("Refusing to overwrite a pregeneration receipt");
    }
    Path temporary = temporary(path);
    try (Writer writer =
        new OutputStreamWriter(
            new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8)) {
      GSON.toJson(value, writer);
    }
    force(temporary);
    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
    int first = committed;
    committed = validateBatch(first);
    checkpoint(first);
  }

  private void checkpoint(int first) throws IOException {
    JsonObject value = new JsonObject();
    value.addProperty("committedColumns", committed);
    value.addProperty("totalColumns", total);
    value.addProperty("lastBatchFirstColumn", first);
    value.addProperty("complete", committed == total);
    value.addProperty("utc", Instant.now().toString());
    atomicJson(output.resolve("checkpoint.json"), value);
  }

  private static JsonObject read(Path path) throws IOException {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return new JsonParser().parse(reader).getAsJsonObject();
    }
  }

  private static Path temporary(Path path) {
    return path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".partial");
  }

  private static void atomicJson(Path path, JsonObject value) throws IOException {
    Path temporary = temporary(path);
    try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
      GSON.toJson(value, writer);
    }
    force(temporary);
    Files.move(
        temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void force(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }
}
