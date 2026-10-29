package io.github.opencubicchunks.cubicchunks.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The provider and lighting sit below cube delivery: they reach the player cube map only through
 * CubeDemand and SurfaceHeightListener, never by its class.
 */
public class TestLayerBoundary {
    private static final String CORE = "src/main/java/io/github/opencubicchunks/cubicchunks/core";

    @Test public void providerAndLightingNeverNameThePlayerCubeMap() throws IOException {
        Path core = core();
        List<Path> below = new ArrayList<>();
        below.add(core.resolve("server/CubeProviderServer.java"));
        try (Stream<Path> lighting = Files.walk(core.resolve("lighting"))) {
            below.addAll(lighting.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()));
        }
        assertTrue("Lighting sources not found under " + core, below.size() > 5);
        List<Path> naming = new ArrayList<>();
        for (Path source : below)
            if (new String(Files.readAllBytes(source), StandardCharsets.UTF_8).contains("PlayerCubeMap")) naming.add(core.relativize(source));
        assertEquals("Name the player cube map below delivery", new ArrayList<Path>(), naming);
    }

    /** Cubature's core sources, found from the test's working directory (the root or a loader build). */
    private static Path core() {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent())
            if (Files.isDirectory(dir.resolve(CORE))) return dir.resolve(CORE);
        throw new AssertionError("No Cubature sources at or above " + start);
    }
}
