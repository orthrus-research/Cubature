package io.github.opencubicchunks.cubicchunks.lab;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Active-context checks without a compile/runtime dependency on either LWJGL generation's context facade. */
final class LabGlCapabilities {
    private static final Probe[] PROBES = {
            probe("org.lwjgl.opengl.GL"), probe("org.lwjgl.opengl.GLContext")
    };
    private LabGlCapabilities() { }

    static boolean openGl15() { return supported(false); }
    static boolean openGl33() { return supported(true); }

    private static boolean supported(boolean version33) {
        for (Probe probe : PROBES) {
            if (probe == null) continue;
            int value = probe.supported(version33);
            if (value >= 0) return value == 1;
        }
        return false;
    }

    private static Probe probe(String name) {
        try {
            Class<?> type = Class.forName(name, false, LabGlCapabilities.class.getClassLoader());
            Method getter = type.getMethod("getCapabilities");
            return new Probe(getter, getter.getReturnType().getField("OpenGL15"), getter.getReturnType().getField("OpenGL33"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return null; }
    }

    private static final class Probe {
        final Method getter;
        final Field openGl15, openGl33;
        Probe(Method getter, Field openGl15, Field openGl33) { this.getter = getter; this.openGl15 = openGl15; this.openGl33 = openGl33; }
        int supported(boolean version33) {
            try {
                Object current = getter.invoke(null, (Object[]) null);
                return current == null ? -1 : (version33 ? openGl33 : openGl15).getBoolean(current) ? 1 : 0;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return -1; }
        }
    }
}
