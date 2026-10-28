package io.github.opencubicchunks.cubicchunks.lab;
import org.junit.Test;
import static org.junit.Assert.*;
public class TestLabCoreFeatures {
    public static final class Feature {public long count(){return 7;}}
    @Test public void unavailableLegacyCapabilityStaysUnknown() {
        java.lang.reflect.Method missing=LabCoreFeatures.optionalMethod(Feature.class,"notPresentInBaseline");
        assertNull(missing);assertNull(LabCoreFeatures.optionalLong(missing,new Feature()));
    }
    @Test public void knownCapabilityReadsActualValue() {
        assertEquals(Long.valueOf(7),LabCoreFeatures.optionalLong(LabCoreFeatures.optionalMethod(Feature.class,"count"),new Feature()));
    }
}
