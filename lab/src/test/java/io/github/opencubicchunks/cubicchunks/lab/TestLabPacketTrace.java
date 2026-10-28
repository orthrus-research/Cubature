package io.github.opencubicchunks.cubicchunks.lab;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
public class TestLabPacketTrace {
    @Test public void scalarTraceIsBoundedAndExportsNoPacketReferences() throws Exception {
        LabPacketTrace trace=new LabPacketTrace();
        for(int i=0;i<32770;i++)trace.add(1,42,99,8,128,100+i,200+i,0,true,0,0,0);
        Map<String,Object> summary=trace.summary();
        assertEquals(32770L,summary.get("count"));assertEquals(32768,summary.get("storedCount"));assertEquals(2L,summary.get("droppedCount"));
        Path directory=Files.createTempDirectory("cubiclab-packets-");
        try {
            Samples.exportSummaries(directory,"packets",summary);
            List<String> lines=Files.readAllLines(directory.resolve("packets.csv"));assertEquals(32769,lines.size());
            assertEquals("1,1,42,99,8,128,100,200,0,1,0,0,0",lines.get(1));
            assertEquals("packets.csv",summary.get("chronologicalCsv"));
        } finally {Files.deleteIfExists(directory.resolve("packets.csv"));Files.delete(directory);}
    }
}
