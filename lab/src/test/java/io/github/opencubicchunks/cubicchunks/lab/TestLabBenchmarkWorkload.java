package io.github.opencubicchunks.cubicchunks.lab;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestLabBenchmarkWorkload {
    private static LabBenchmarkWorkload read(String extra){
        String json="{\"schema\":1,\"id\":\"contract\",\"seed\":-9223372036854775808,\"camera\":[-0.5,80,-16.5,170,20]"+extra+"}";
        return new LabBenchmarkWorkload(new JsonParser().parse(json).getAsJsonObject(),json.getBytes(StandardCharsets.UTF_8));
    }
    @Test public void legacyDefaultsKeepBothFullHeightCohorts(){
        LabBenchmarkWorkload w=new LabBenchmarkWorkload(null,null);
        assertNull(w.identity);assertEquals(128,w.originX);assertEquals(32,w.offsetX);assertEquals(4,w.width);assertEquals(4,w.depth);
        assertEquals(8,w.batchSize);assertEquals(16,w.asyncConcurrency);assertEquals("GENERATE",w.requirement);
    }
    @Test public void explicitNegativeFixtureAndLongSeedArePreserved(){
        LabBenchmarkWorkload w=read(",\"fixture\":{\"originColumn\":[-64,-128],\"sizeColumns\":[1,2],\"cohortOffset\":[0,8],\"requirement\":\"LIGHT\"}");
        assertEquals(Long.MIN_VALUE,w.seed);assertEquals(-64,w.originX);assertEquals(-128,w.originZ);assertEquals(8,w.offsetZ);assertEquals("LIGHT",w.requirement);
    }
    @Test public void steppedRouteHoldsExactCoordinatesAndMovesOnlyAtTransitions(){
        LabBenchmarkWorkload w=read(",\"route\":{\"waypoints\":[{\"pose\":[-111.5,102,-2044.5,135,22],\"ticks\":40},{\"pose\":[-623.5,160,-2300.5,45,28],\"ticks\":80}]}");
        assertEquals(120,w.routeTicks);assertEquals(0,w.waypoint(39));assertEquals(1,w.waypoint(40));
        assertArrayEquals(new double[]{-111.5,102,-2044.5,135,22},w.position(39),0);
        assertArrayEquals(new double[]{-623.5,160,-2300.5,45,28},w.position(119),0);
        assertTrue(w.moveAt(0));assertTrue(w.moveAt(40));assertFalse(w.moveAt(39));
    }
    @Test public void interpolationUsesShortestYawAndEndsAtDeclaredWaypoint(){
        LabBenchmarkWorkload w=read(",\"route\":{\"interpolation\":\"linear\",\"waypoints\":[{\"pose\":[0,80,0,170,20],\"ticks\":3},{\"pose\":[32,96,-32,-170,30],\"ticks\":2}]}");
        assertArrayEquals(new double[]{16,88,-16,180,25},w.position(1),0);
        assertEquals(190,w.position(2)[3],0);assertEquals(-170,w.position(4)[3],0);assertTrue(w.moveAt(1));
    }
    @Test public void peerIdentityIncludesTheExactInputBytes(){
        LabBenchmarkWorkload w=read("");JsonObject peer=new JsonObject();peer.add("benchmarkWorkload",new Gson().toJsonTree(w.identity));w.verifyPeer(peer);
        peer.getAsJsonObject("benchmarkWorkload").addProperty("sha256","changed");
        try{w.verifyPeer(peer);fail("Mismatched peer accepted");}catch(IllegalArgumentException expected){}
    }
    @Test(expected=IllegalArgumentException.class) public void overlappingCohortsFail(){read(",\"fixture\":{\"cohortOffset\":[1,1]}");}
    @Test(expected=IllegalArgumentException.class) public void outOfHeightRouteFails(){read(",\"route\":{\"waypoints\":[{\"pose\":[0,384,0,0,0],\"ticks\":40}]}");}
    @Test(expected=IllegalArgumentException.class) public void unboundedRouteFails(){read(",\"route\":{\"waypoints\":[{\"pose\":[0,80,0,0,0],\"ticks\":12001}]}");}
}
