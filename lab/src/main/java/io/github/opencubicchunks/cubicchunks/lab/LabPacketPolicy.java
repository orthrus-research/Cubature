package io.github.opencubicchunks.cubicchunks.lab;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Optional diagnostics must not require a packet experiment in the production runtime. */
final class LabPacketPolicy {
    private LabPacketPolicy() { }

    static Integer optionalInt(Class<?> owner, String name) {
        try {
            Field field = owner.getField(name);
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException("Unsupported packet policy field: " + name);
            }
            return field.getInt(null);
        } catch (NoSuchFieldException absent) {
            return null;
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot inspect packet policy field: " + name, error);
        }
    }
}
