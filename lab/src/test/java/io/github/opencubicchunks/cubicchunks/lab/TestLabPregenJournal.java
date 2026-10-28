package io.github.opencubicchunks.cubicchunks.lab;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestLabPregenJournal {
  private static final String INPUTS =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private JsonObject definition() {
    JsonObject value = new JsonObject();
    value.addProperty("inputsSha256", INPUTS);
    value.addProperty("widthColumns", 4);
    value.addProperty("originChunkX", -2);
    value.addProperty("originChunkZ", -2);
    return value;
  }

  private Map<String, Object> batch(int first, int end) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("firstColumn", first);
    result.put("endColumn", end);
    result.put("inputsSha256", INPUTS);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = first; i < end; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("chunkX", -2 + i % 4);
      row.put("chunkZ", -2 + i / 4);
      row.put("cubes", 32);
      rows.add(row);
    }
    result.put("rows", rows);
    return result;
  }

  @Test
  public void resumesFromSavedCheckpoint() throws IOException {
    Path root = temporary.newFolder().toPath();
    LabPregenJournal journal = new LabPregenJournal(root, definition(), 16);
    journal.append(batch(0, 4));
    journal.append(batch(4, 8));
    assertEquals(8, new LabPregenJournal(root, definition(), 16).committed());
  }

  @Test
  public void recoversBatchPublishedBeforeCheckpoint() throws IOException {
    Path root = temporary.newFolder().toPath();
    LabPregenJournal journal = new LabPregenJournal(root, definition(), 16);
    journal.append(batch(0, 4));
    byte[] oldCheckpoint = Files.readAllBytes(root.resolve("checkpoint.json"));
    journal.append(batch(4, 8));
    Files.write(root.resolve("checkpoint.json"), oldCheckpoint);
    assertEquals(8, new LabPregenJournal(root, definition(), 16).committed());
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsDifferentInputsOnResume() throws IOException {
    Path root = temporary.newFolder().toPath();
    new LabPregenJournal(root, definition(), 16);
    JsonObject changed = definition();
    changed.addProperty(
        "inputsSha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    new LabPregenJournal(root, changed, 16);
  }

  @Test(expected = IOException.class)
  public void refusesNoncontiguousAppend() throws IOException {
    LabPregenJournal journal =
        new LabPregenJournal(temporary.newFolder().toPath(), definition(), 16);
    journal.append(batch(4, 8));
  }

  @Test(expected = IOException.class)
  public void rejectsMalformedCoordinatesBeforePublishing() throws IOException {
    Path root = temporary.newFolder().toPath();
    LabPregenJournal journal = new LabPregenJournal(root, definition(), 16);
    Map<String, Object> value = batch(0, 4);
    value.put("rows", batch(4, 8).get("rows"));
    journal.append(value);
  }
}
