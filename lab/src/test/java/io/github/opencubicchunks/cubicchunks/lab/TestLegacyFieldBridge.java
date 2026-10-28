package io.github.opencubicchunks.cubicchunks.lab;
import com.cleanroommc.hackery.ReflectionHackery;
import org.junit.Test;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
public class TestLegacyFieldBridge {
    public static final class ColdTarget {
        private static final Object value = initial();
        private static Object initial() { return new Object(); }
        static Object read() { return value; }
    }
    public static final class InstanceTarget { private int value = 17; }
    @Test public void injectionInitializesClassBeforeWritingItsStaticFinalField() throws Exception {
        Field field = ColdTarget.class.getDeclaredField("value");
        Object replacement = new Object();
        ReflectionHackery.setField(field, null, replacement);
        assertSame(replacement, ColdTarget.read());
        assertSame(replacement, ReflectionHackery.getField(field, null));
    }
    @Test public void primitiveFieldAccessPreservesInstanceOwnership() throws Exception {
        Field field = InstanceTarget.class.getDeclaredField("value");
        InstanceTarget a = new InstanceTarget(), b = new InstanceTarget();
        ReflectionHackery.setIntField(field, a, 91);
        assertEquals(91, ReflectionHackery.getIntField(field, a));
        assertEquals(17, ReflectionHackery.getIntField(field, b));
    }
}
