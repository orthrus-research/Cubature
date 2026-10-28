package io.github.opencubicchunks.cubicchunks.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Post-capture location evidence only; never changes renderer readiness or requests cubes. */
final class LabMeshDemandSnapshot implements Consumer<int[]> {
    private final LabViewGeometry view;
    private final int[][] counts=new int[3][4];
    private final List<int[]> samples=new ArrayList<>();
    LabMeshDemandSnapshot(LabViewGeometry view){this.view=view;}
    @Override public void accept(int[] section){
        if(section.length!=6)throw new IllegalArgumentException("Expected cube XYZ, uncompiled/skipped/dirty flags");
        int x=section[0],y=section[1],z=section[2];
        boolean inside=x>=view.minX&&x<=view.maxX&&y>=view.minY&&y<=view.maxY&&z>=view.minZ&&z<=view.maxZ;
        boolean interior=inside&&x>view.minX&&x<view.maxX&&y>view.minY&&y<view.maxY&&z>view.minZ&&z<view.maxZ;
        int category=!inside?2:interior?0:1;counts[category][0]++;
        for(int i=0;i<3;i++){if(section[i+3]<0||section[i+3]>1)throw new IllegalArgumentException("Invalid demand flag");counts[category][i+1]+=section[i+3];}
        if(samples.size()<128)samples.add(section.clone());
    }
    Map<String,Object> summary(){
        Map<String,Object> result=new LinkedHashMap<>();String[] labels={"interior","boundary","outsideTarget"};
        for(int i=0;i<3;i++){
            Map<String,Object> row=new LinkedHashMap<>();row.put("sections",counts[i][0]);row.put("uncompiled",counts[i][1]);row.put("skipped",counts[i][2]);row.put("dirty",counts[i][3]);result.put(labels[i],row);
        }
        result.put("samplesXYZUncompiledSkippedDirty",new ArrayList<>(samples));result.put("sampleLimit",128);
        result.put("sampledSections",samples.size());result.put("totalSections",counts[0][0]+counts[1][0]+counts[2][0]);
        result.put("scope","Post-capture outstanding frustum sections relative to the exact data target. Interior sections have all six face neighbors inside that target; only full residency establishes their presence. Boundary classification does not prove exterior neighbors absent. No readiness predicate is relaxed, no cube requested, no presentation claim.");return result;
    }
}
