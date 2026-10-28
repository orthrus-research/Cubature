package com.cleanroommc.hackery;

import java.lang.reflect.Field;
import zone.rong.imaginebreaker.Index;

/**
 * Lab-only ABI bridge for Fugue 0.24.4's field-access transformer on Cleanroom 0.7.
 * Calls use the loader's current ImagineBreaker API; no third-party JAR is rewritten.
 * This is the field-access subset, not a replacement for the complete historical API.
 */
public final class ReflectionHackery {
    private ReflectionHackery() { }
    private static zone.rong.imaginebreaker.api.ImagineBreaker ready(Field field) {
        zone.rong.imaginebreaker.api.ImagineBreaker api = Index.get();
        api.ensureInitialized(field.getDeclaringClass());
        return api;
    }
    public static Object getField(Field field, Object target) { return ready(field).get(target, field); }
    public static boolean getBooleanField(Field field, Object target) { return ready(field).getBoolean(target, field); }
    public static byte getByteField(Field field, Object target) { return ready(field).getByte(target, field); }
    public static char getCharField(Field field, Object target) { return ready(field).getChar(target, field); }
    public static short getShortField(Field field, Object target) { return ready(field).getShort(target, field); }
    public static int getIntField(Field field, Object target) { return ready(field).getInt(target, field); }
    public static long getLongField(Field field, Object target) { return ready(field).getLong(target, field); }
    public static float getFloatField(Field field, Object target) { return ready(field).getFloat(target, field); }
    public static double getDoubleField(Field field, Object target) { return ready(field).getDouble(target, field); }
    public static void setField(Field field, Object target, Object value) { ready(field).set(target, field, value); }
    public static void setBooleanField(Field field, Object target, boolean value) { ready(field).setBoolean(target, field, value); }
    public static void setByteField(Field field, Object target, byte value) { ready(field).setByte(target, field, value); }
    public static void setCharField(Field field, Object target, char value) { ready(field).setChar(target, field, value); }
    public static void setShortField(Field field, Object target, short value) { ready(field).setShort(target, field, value); }
    public static void setIntField(Field field, Object target, int value) { ready(field).setInt(target, field, value); }
    public static void setLongField(Field field, Object target, long value) { ready(field).setLong(target, field, value); }
    public static void setFloatField(Field field, Object target, float value) { ready(field).setFloat(target, field, value); }
    public static void setDoubleField(Field field, Object target, double value) { ready(field).setDouble(target, field, value); }
}
