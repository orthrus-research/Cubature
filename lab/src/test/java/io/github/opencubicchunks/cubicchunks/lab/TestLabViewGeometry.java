package io.github.opencubicchunks.cubicchunks.lab;

import org.junit.Test;
import static org.junit.Assert.*;

public class TestLabViewGeometry {
    @Test public void targetSetMatchesDefaultAndRoundTrips() {
        LabViewGeometry g=new LabViewGeometry(10112.5,288,4256.5);
        assertEquals(620,g.minX);assertEquals(644,g.maxX);assertEquals(6,g.minY);assertEquals(30,g.maxY);
        assertEquals(254,g.minZ);assertEquals(278,g.maxZ);
        assertEquals(15625,(g.maxX-g.minX+1)*(g.maxY-g.minY+1)*(g.maxZ-g.minZ+1));
        assertEquals(g.minX,LabViewGeometry.read(g.json()).minX);
    }
    @Test public void negativeCoordinatesUseFloorRatherThanTruncation() {
        LabViewGeometry g=new LabViewGeometry(-.5,-.5,-16.5);
        assertEquals(-13,g.minX);assertEquals(-13,g.minY);assertEquals(-14,g.minZ);
    }
    @Test(expected=IllegalArgumentException.class)public void invalidCameraFails() {new LabViewGeometry(Double.NaN,64,0);}
    @Test(expected=IllegalArgumentException.class)public void reducedViewContractFails() {
        com.google.gson.JsonObject data=new LabViewGeometry(0,64,0).json();data.addProperty("expected",1);LabViewGeometry.read(data);
    }
    @Test public void cameraOrientationRoundTripsWithoutChangingCubeCoverage(){
        LabViewGeometry g=new LabViewGeometry(-.5,102,-2044.5,135,22),read=LabViewGeometry.read(g.json());
        assertEquals(135,read.yaw,0);assertEquals(22,read.pitch,0);assertEquals(g.minY,read.minY);assertEquals(15625,LabViewGeometry.EXPECTED);
    }

    @Test public void usesTheWorkloadPoseForRendererReadiness() {
        LabViewGeometry view=new LabViewGeometry(8102.5,144,4552.5,69.02374f,25.89447f);
        assertTrue(view.matchesPose(8102.5,144,4552.5,69.02374f,25.89447f));
        assertFalse(view.matchesPose(8102.5,144,4552.5,0,28));
        assertFalse(view.matchesPose(8103,144,4552.5,69.02374f,25.89447f));
        assertFalse(view.matchesPose(Double.NaN,144,4552.5,69.02374f,25.89447f));
        assertFalse(view.matchesPose(8102.5,144,4552.5,Float.NaN,25.89447f));
    }
    @Test public void equivalentYawWrapsPreserveTheSameView() {
        LabViewGeometry view=new LabViewGeometry(0,144,0,-179,20);
        assertTrue(view.matchesPose(0,144,0,181,20));
        assertFalse(view.matchesPose(0,144,0,180,20));
    }
}
