package io.github.opencubicchunks.cubicchunks.core.compat.demonica;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import org.junit.After;
import org.junit.Test;

public class TestDemonicaCompat {
    // Complementary r5.9.3's lib/atmospherics/fog/caveFactor.glsl, byte for byte (CRLF), in Reimagined and Unbound.
    private static final String CAVE_FACTOR = "#ifndef INCLUDE_CAVE_FACTOR\r\n"
            + "    #define INCLUDE_CAVE_FACTOR\r\n"
            + "\r\n"
            + "    float GetCaveFactor() {\r\n"
            + "        float caveFactor = clamp(1.0 - cameraPosition.y / oceanAltitude, 0.0, 1.0 - eyeBrightnessM);\r\n"
            + "        #ifdef SULFUR_CAVE_FOG\r\n"
            + "            caveFactor = mix(caveFactor, 1.0, inSulfurCaves);\r\n"
            + "        #endif\r\n"
            + "        return caveFactor;\r\n"
            + "    }\r\n"
            + "#endif\r\n";
    // As Demonica resolves it: relative inside a zip, or under a pack folder.
    private static final Path IN_ZIP = Paths.get("shaders", "lib", "atmospherics", "fog", "caveFactor.glsl");
    private static final Path IN_FOLDER = Paths.get("shaderpacks", "Pack", "shaders", "lib", "atmospherics", "fog", "caveFactor.glsl");

    @After public void restore() { CubicChunksConfig.complementaryCaveFog = false; }

    @Test public void offByDefaultThePackIsReadAsIs() {
        assertEquals(false, CubicChunksConfig.complementaryCaveFog);
        assertNull(DemonicaCompat.patchPackSource(IN_ZIP, CAVE_FACTOR));
    }

    @Test public void caveFogFollowsTheDepthBelowTheSurface() {
        CubicChunksConfig.complementaryCaveFog = true;
        String patched = DemonicaCompat.patchPackSource(IN_ZIP, CAVE_FACTOR);
        assertEquals(CAVE_FACTOR.split("\r\n").length, patched.split("\r\n").length);
        assertTrue(patched.contains("    uniform float cubicSurfaceDepth; float GetCaveFactor() {\r\n"));
        assertTrue(patched.contains(
                "float caveFactor = clamp(cubicSurfaceDepth / oceanAltitude, 0.0, 1.0 - eyeBrightnessM);\r\n"));
        assertEquals(-1, patched.indexOf("cameraPosition.y"));
        assertEquals(patched, DemonicaCompat.patchPackSource(IN_FOLDER, CAVE_FACTOR));
    }

    @Test public void otherFilesAndOtherTextAreLeftAlone() {
        CubicChunksConfig.complementaryCaveFog = true;
        assertNull(DemonicaCompat.patchPackSource(Paths.get("shaders", "lib", "common.glsl"), CAVE_FACTOR));
        assertNull(DemonicaCompat.patchPackSource(Paths.get("shaders", "lib", "notcaveFactor.glsl"), CAVE_FACTOR));
        assertNull(DemonicaCompat.patchPackSource(IN_ZIP, CAVE_FACTOR.replace("1.0 - cameraPosition.y", "2.0 - cameraPosition.y")));
        assertNull(DemonicaCompat.patchPackSource(IN_ZIP, CAVE_FACTOR + "\r\n" + CAVE_FACTOR));
    }
}
