package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import java.io.IOException;
import java.util.*;

/** Owner-thread fixtures with explicit save semantics; never substitutes the periodic-save trigger. */
final class LabSaveContracts {
    private final String kind;
    private int initialTick, saveEvents, finalTick, cleanFlushes, naturalEvents;
    private final List<Integer> eventTicks=new ArrayList<>();
    private boolean prepared, issued, initialized;
    LabSaveContracts(String kind){this.kind=kind;}
    void prepare(LabJob job)throws IOException{
        if(prepared)return;prepared=true;
        job.saveAndFlush("preflightSaveContract",false);
    }
    void worldSaved(int tick){saveEvents++;eventTicks.add(tick);if(tick%900==0)naturalEvents++;}
    boolean tick(LabJob job)throws IOException{
        if(!initialized){initialized=true;initialTick=job.server.getTickCounter();
            if(!kind.equals("save-clean"))for(CubePos pos:job.targets)job.provider.getLoadedCube(pos).markDirty();}
        if(kind.equals("save-command")){
            if(!issued){issued=true;long start=System.nanoTime();
                int success=job.server.getCommandManager().executeCommand(job.server,"save-all flush");
                job.stages.record("completeSaveAllFlushCommand",start);job.operations.add(System.nanoTime()-start);
                if(success!=1||saveEvents==0)throw new IllegalStateException("Full save command or WorldEvent.Save was not observed");
                job.operationCount=1;
            }
        }else if(kind.equals("save-clean")){
            long start=System.nanoTime();job.provider.getCubeIO().flush();job.stages.record("cleanDurabilityFlush",start);
            job.operations.add(System.nanoTime()-start);job.operationCount=++cleanFlushes;
            if(cleanFlushes<5)return false;
        }else{
            if(naturalEvents==0)return false;
            if(job.provider.getPendingAutosaveCount()!=0)return false;
            for(CubePos pos:job.targets){Cube cube=job.provider.getLoadedCube(pos);if(cube.needsSaving())return false;}
            long start=System.nanoTime();job.provider.getCubeIO().flush();job.flushNanos+=System.nanoTime()-start;
            job.stages.record("naturalAutosaveFinalBarrier",start);job.operationCount=job.targets.size();
        }
        finalTick=job.server.getTickCounter();return true;
    }
    Map<String,Object> summary(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("kind",kind);out.put("initialServerTick",initialTick);
        out.put("finalServerTick",finalTick);out.put("worldSaveEvents",saveEvents);out.put("naturalCadenceEvents",naturalEvents);out.put("saveEventServerTicks",eventTicks);out.put("cleanFlushes",cleanFlushes);
        out.put("scope",kind.equals("autosave-natural")?"Observed actual 900-tick server periodic save; no synthetic AutosaveScope call. Final durability barrier is timed separately.":kind.equals("save-command")?"Actual command manager save-all flush, including players, metadata and world save events; later independent readback remains required.":"Five explicit flushes after preflight drain, without new lab snapshots; ordinary world activity continues.");return out;
    }
}
