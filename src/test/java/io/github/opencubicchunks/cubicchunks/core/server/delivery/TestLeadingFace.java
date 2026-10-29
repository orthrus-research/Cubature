package io.github.opencubicchunks.cubicchunks.core.server.delivery;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TestLeadingFace {
    @Test public void exactlyOneNewFaceOutsideEveryHorizontalViewIncludingNegativeCoordinates() {
        for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            List<CubePos> positions=LeadingFace.positions(-506,9,-284,12,12,direction[0],direction[1]);
            assertEquals(625,positions.size());assertEquals(625,new HashSet<>(positions).size());
            for(CubePos p:positions) {
                assertTrue(Math.abs(p.getY()-9)<=12);
                if(direction[0]!=0){assertEquals(-506+13*direction[0],p.getX());assertTrue(Math.abs(p.getZ()+284)<=12);}
                else {assertEquals(-284+13*direction[1],p.getZ());assertTrue(Math.abs(p.getX()+506)<=12);}
            }
            CubePos first=positions.get(0);
            assertEquals(9,first.getY());
            assertEquals(-506+13*direction[0],first.getX());assertEquals(-284+13*direction[1],first.getZ());
        }
    }
    @Test public void noDirectionAndUnboundedFacesDoNotAllocateSpeculativeWork() {
        assertTrue(LeadingFace.positions(0,0,0,12,12,0,0).isEmpty());
        assertTrue(LeadingFace.positions(0,0,0,12,12,1,1).isEmpty());
        assertTrue(LeadingFace.positions(0,0,0,100000,100000,1,0).isEmpty());
    }
    @Test public void advancingViewMakesOldFaceMandatoryAndNewSpeculationDisjoint() {
        Set<CubePos> old=new HashSet<>(LeadingFace.positions(0,0,0,12,12,1,0));
        Set<CubePos> next=new HashSet<>(LeadingFace.positions(1,0,0,12,12,1,0));
        assertTrue(Collections.disjoint(old,next));
        for(CubePos p:old)assertTrue(Math.abs(p.getX()-1)<=12);
    }
}
