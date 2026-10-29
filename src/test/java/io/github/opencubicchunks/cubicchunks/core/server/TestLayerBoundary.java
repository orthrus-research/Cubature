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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The provider and lighting sit below cube delivery: they reach the player cube map only through
 * CubeDemand and SurfaceHeightListener, never by its class. Cube delivery, in core.server.delivery, reaches the
 * server package only through the provider, cube watchers and the vanilla network handler.
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

    @Test public void deliveryNamesOnlyItsServerCollaborators() throws IOException {
        Path core = core();
        List<String> allowed = Arrays.asList("CubeProviderServer", "CubeWatcher", "VanillaNetworkHandler");
        Pattern server = Pattern.compile("core\\.server\\.(\\w+|\\*)");
        List<Path> sources;
        try (Stream<Path> delivery = Files.walk(core.resolve("server/delivery"))) {
            sources = delivery.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue("Delivery sources not found under " + core, sources.size() > 10);
        List<String> named = new ArrayList<>();
        for (Path source : sources) {
            Matcher match = server.matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
            while (match.find())
                if (!match.group(1).equals("delivery") && !allowed.contains(match.group(1))) named.add(core.relativize(source) + ": " + match.group(1));
        }
        assertEquals("Reach the server package from delivery beyond its collaborators", new ArrayList<String>(), named);
    }

    /** Cubature's core sources, found from the test's working directory (the root or a loader build). */
    private static Path core() {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent())
            if (Files.isDirectory(dir.resolve(CORE))) return dir.resolve(CORE);
        throw new AssertionError("No Cubature sources at or above " + start);
    }
}
