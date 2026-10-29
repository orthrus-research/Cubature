package io.github.opencubicchunks.cubicchunks.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.DeliveryMode;
import net.minecraft.command.CommandException;
import net.minecraftforge.common.config.Config;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Config values are checked where they enter: on every sync, and by the set command before it sets anything. */
public class TestConfigBoundary {
    /** Options shaped like CubicChunksConfig's, for the range walk and the set command. */
    public static class Options {
        @Config.RangeInt(min = 1, max = 10) public static int count = 5;
        @Config.RangeDouble(min = -1, max = 1) public static float factor = 0;
        public static DeliveryMode mode = DeliveryMode.BASELINE;
        public static Map<String, Integer> limits = new HashMap<>();
        public static int[] values = {1};
        public static Nested nested = new Nested();

        public static class Nested {
            @Config.RangeInt(min = 16, max = 64) public int size = 32;
        }
    }

    @Test public void syncBringsEveryDeclaredRangeBackToItsNearestBound() throws Exception {
        Options.count = 50;
        Options.factor = -3;
        Options.nested.size = 2;
        Method clamp = CubicChunksConfig.class.getDeclaredMethod("clampToDeclaredRanges", Object.class, Field[].class, String.class);
        clamp.setAccessible(true);
        clamp.invoke(null, null, Options.class.getFields(), "");
        assertEquals(10, Options.count);
        assertEquals(-1, Options.factor, 0);
        assertEquals(16, Options.nested.size);
        Options.count = 7;
        clamp.invoke(null, null, Options.class.getFields(), "");
        assertEquals("A value inside its range is kept", 7, Options.count);
    }

    @Test public void theSetCommandParsesAndChecksBeforeSetting() throws Exception {
        assertEquals(7, parse("count", "7"));
        rejects("count", "50");
        rejects("count", "0");
        assertEquals(DeliveryMode.PREFETCH, parse("mode", "PREFETCH"));
        rejects("mode", "SOON");
        assertEquals(Collections.singletonMap("tickets", 3), parse("limits", "tickets=3"));
        rejects("limits", "tickets");
        assertArrayEquals("An array option takes no arguments to mean empty", new int[0], (int[]) parse("values"));
    }

    @Test public void theSetCommandReportsBoxedValues() throws Exception {
        CubicChunksConfig.SetConfig command = command("count");
        Method stringify = CubicChunksConfig.SetConfig.class.getDeclaredMethod("stringify", Object.class);
        stringify.setAccessible(true);
        assertEquals("5", stringify.invoke(command, 5));
        assertEquals("true", stringify.invoke(command, true));
        assertEquals("[1, 2]", stringify.invoke(command, (Object) new int[]{1, 2}));
        assertEquals("null", stringify.invoke(command, (Object) null));
    }

    private static CubicChunksConfig.SetConfig command(String name) throws NoSuchFieldException {
        return new CubicChunksConfig.SetConfig(name, null, Options.class.getField(name), false);
    }

    private static Object parse(String name, String... args) throws Exception {
        Method parse = CubicChunksConfig.SetConfig.class.getDeclaredMethod("parseConfigValue", String[].class);
        parse.setAccessible(true);
        try {
            return parse.invoke(command(name), (Object) args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    private static void rejects(String name, String... args) throws Exception {
        try {
            parse(name, args);
            fail(name + " accepted " + String.join(" ", args));
        } catch (CommandException expected) {
            assertTrue(expected.getMessage(), expected.getMessage() != null);
        }
    }
}
