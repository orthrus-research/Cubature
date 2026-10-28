package io.github.opencubicchunks.cubicchunks.lab;

import org.junit.Test;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import static org.junit.Assert.*;

public class TestLabStorageTrace {
    @Test public void lightweightLifecycleDoesNotReportUnmeasuredQueueBytesAsZero(){
        String prior=System.getProperty("cubiclab.storageDiagnostics"), accounting=System.getProperty("cubiclab.storageQueueAccounting");
        System.setProperty("cubiclab.storageDiagnostics","true");System.setProperty("cubiclab.storageQueueAccounting","false");
        try{
            LabStorageTrace.start();
            io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage.NBTBatch input=new io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage.NBTBatch(java.util.Collections.emptyMap(),java.util.Collections.emptyMap());
            LabStorageTrace.Batch batch=LabStorageTrace.beginBatch(input);LabStorageTrace.compressed(42);LabStorageTrace.endBatch(batch);
            Map<String,Object> result=LabStorageTrace.finish();assertEquals(false,result.get("queueByteAccountingEnabled"));
            assertNull(result.get("pendingEncodedNbtBytesMaximum"));assertNull(result.get("inFlightEncodedNbtBytesMaximum"));assertEquals(42L,result.get("compressedBytesProducedPerBatchMaximum"));
        }finally{LabStorageTrace.finish();if(prior==null)System.clearProperty("cubiclab.storageDiagnostics");else System.setProperty("cubiclab.storageDiagnostics",prior);if(accounting==null)System.clearProperty("cubiclab.storageQueueAccounting");else System.setProperty("cubiclab.storageQueueAccounting",accounting);}
    }
    @Test public void exactCallsAndFailuresSurviveTraceOverflow(){
        LabStorageTrace trace=new LabStorageTrace(1);Object owner=new Object();
        trace.exit(trace.enter("force",owner,100),120,true,8);
        trace.exit(trace.enter("force",owner,130),160,false,8);
        Map<String,Object> summary=trace.summary();
        assertEquals(2L,summary.get("events"));assertEquals(0L,summary.get("openSpansAtSeal"));
        assertEquals(1L,((Map<?,?>)summary.get("failures")).get("force"));
        assertEquals(1L,((Map<?,?>)summary.get("trace")).get("droppedCount"));
        Map<?,?> timing=(Map<?,?>)((Map<?,?>)summary.get("timings")).get("force");
        assertEquals(2L,timing.get("count"));assertEquals(false,timing.get("percentilesCoverAllObservations"));
    }
    @Test public void unfinishedSpanIsExplicit(){
        LabStorageTrace trace=new LabStorageTrace(4);trace.enter("blocked",this,100);
        assertEquals(1L,trace.summary().get("openSpansAtSeal"));
    }
    @Test public void doubleEndRejected(){
        LabStorageTrace trace=new LabStorageTrace(4);LabStorageTrace.Token token=trace.enter("force",this,100);
        trace.exit(token,120,true,0);
        try{trace.exit(token,130,true,0);fail();}catch(IllegalStateException expected){}
    }
    @Test public void byteAccountingIsSerializedNbtNotHeapEstimate(){
        NBTTagCompound nbt=new NBTTagCompound();nbt.setByteArray("x",new byte[8192]);
        // unnamed compound(3), byte-array tag id+UTF name+length(8), bytes, END(1)
        assertEquals(8204L,LabStorageTrace.encodedBytes(nbt));
    }
    @Test public void batchAccountingTracksBytesAndReleasesOwnership(){
        String previous=System.getProperty("cubiclab.storageDiagnostics");System.setProperty("cubiclab.storageDiagnostics","true");
        try{
            LabStorageTrace.start();NBTTagCompound nbt=new NBTTagCompound();nbt.setByteArray("x",new byte[8192]);
            io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage.NBTBatch input=new io.github.opencubicchunks.cubicchunks.api.world.storage.ICubicStorage.NBTBatch(java.util.Collections.emptyMap(),java.util.Collections.singletonMap(new io.github.opencubicchunks.cubicchunks.api.util.CubePos(0,0,0),nbt));
            LabStorageTrace.Batch current=LabStorageTrace.beginBatch(input);
            try{LabStorageTrace.beginBatch(input);fail();}catch(IllegalStateException expected){}
            LabStorageTrace.compressed(100);LabStorageTrace.compressed(200);LabStorageTrace.endBatch(current);
            Map<String,Object> out=LabStorageTrace.finish();assertEquals(8204L,out.get("inFlightEncodedNbtBytesMaximum"));assertEquals(0L,out.get("inFlightEncodedNbtBytesAtSeal"));assertEquals(300L,out.get("compressedBytesProducedPerBatchMaximum"));
        }finally{LabStorageTrace.finish();if(previous==null)System.clearProperty("cubiclab.storageDiagnostics");else System.setProperty("cubiclab.storageDiagnostics",previous);}
    }
}
