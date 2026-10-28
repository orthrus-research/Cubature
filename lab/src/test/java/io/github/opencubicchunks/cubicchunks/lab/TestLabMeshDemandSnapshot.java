package io.github.opencubicchunks.cubicchunks.lab;

import org.junit.Test;
import java.util.Map;
import java.util.List;
import static org.junit.Assert.*;

public class TestLabMeshDemandSnapshot {
    @Test public void separatesCompleteInteriorFromAllSixBoundaryFacesAndOutsideSections(){
        LabViewGeometry g=new LabViewGeometry(.5,80,-.5);LabMeshDemandSnapshot snapshot=new LabMeshDemandSnapshot(g);
        for(int x=g.minX;x<=g.maxX;x++)for(int y=g.minY;y<=g.maxY;y++)for(int z=g.minZ;z<=g.maxZ;z++)snapshot.accept(new int[]{x,y,z,1,1,0});
        snapshot.accept(new int[]{g.minX-1,5,-1,1,0,1});snapshot.accept(new int[]{0,g.maxY+1,-1,0,1,0});snapshot.accept(new int[]{0,5,g.maxZ+1,0,0,1});
        Map<String,Object> result=snapshot.summary();
        assertEquals(12167,((Map<?,?>)result.get("interior")).get("uncompiled"));
        assertEquals(3458,((Map<?,?>)result.get("boundary")).get("skipped"));
        Map<?,?> outside=(Map<?,?>)result.get("outsideTarget");assertEquals(3,outside.get("sections"));assertEquals(1,outside.get("uncompiled"));assertEquals(1,outside.get("skipped"));assertEquals(2,outside.get("dirty"));
        assertEquals(15628,result.get("totalSections"));assertEquals(128,result.get("sampledSections"));
    }
    @Test public void retainsBoundedCoordinateCopiesWithNegativeHeightAndSnapshotIndependence(){
        LabViewGeometry g=new LabViewGeometry(-.5,-127,-.5);LabMeshDemandSnapshot snapshot=new LabMeshDemandSnapshot(g);
        int[] section={-1,-8,-1,1,0,0};snapshot.accept(section);section[0]=900;
        Map<String,Object> first=snapshot.summary();snapshot.accept(new int[]{g.maxX,g.maxY,g.maxZ,0,1,0});
        assertEquals(1,first.get("totalSections"));assertEquals(1,((List<?>)first.get("samplesXYZUncompiledSkippedDirty")).size());
        assertEquals(-1,((int[])((List<?>)first.get("samplesXYZUncompiledSkippedDirty")).get(0))[0]);
        assertEquals(2,snapshot.summary().get("totalSections"));
    }
}
