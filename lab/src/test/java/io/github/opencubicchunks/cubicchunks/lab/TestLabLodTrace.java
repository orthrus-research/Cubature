package io.github.opencubicchunks.cubicchunks.lab;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.junit.Test;

public class TestLabLodTrace {
    enum State { SUCCESS, REQUIRES_SPLITTING, OTHER }
    static final class Result { final State state; Result(State state){this.state=state;} }

    @Test public void packedPositionsMatchDhLayout() {
        for (int[] p : new int[][]{{6,0,0},{6,-1,-1},{10,123,-456},{18,-134217728,134217727}}) {
            long pos=LabLodTrace.encode(p[0],p[1],p[2]);
            assertEquals(p[0],LabLodTrace.detail(pos));assertEquals(p[1],LabLodTrace.x(pos));assertEquals(p[2],LabLodTrace.z(pos));
        }
        // DhSectionPos.encode(6, 1, 2): detail in the low byte, x from bit 8, z from bit 36.
        assertEquals(6L|(1L<<8)|(2L<<36),LabLodTrace.encode(6,1,2));
    }

    @Test public void outcomesNameEveryRetrievalResult() {
        assertEquals(1,LabLodTrace.outcome(new Result(State.SUCCESS),null));
        assertEquals(2,LabLodTrace.outcome(new Result(State.REQUIRES_SPLITTING),null));
        assertEquals(3,LabLodTrace.outcome(new Result(State.OTHER),null));
        assertEquals(-2,LabLodTrace.outcome(null,new CompletionException(new CancellationException())));
        assertEquals(-1,LabLodTrace.outcome(null,new IllegalStateException()));
    }

    /** The enabled path builds INSTANCE during class initialization; every static it reads must exist by then. */
    @Test public void initializesWhenEnabled() throws Exception {
        Path dir=Files.createTempDirectory("lod-trace-enabled");
        String prefix=LabLodTrace.class.getName();
        ClassLoader isolated=new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(!name.startsWith(prefix))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> loaded=findLoadedClass(name);
                    if(loaded!=null)return loaded;
                    try(java.io.InputStream in=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                        if(in==null)throw new ClassNotFoundException(name);
                        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                        byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))>0;)bytes.write(buffer,0,n);
                        return defineClass(name,bytes.toByteArray(),0,bytes.size());
                    } catch(java.io.IOException e) { throw new ClassNotFoundException(name,e); }
                }
            }
        };
        String previous=System.getProperty(LabLodTrace.PROPERTY);
        System.setProperty(LabLodTrace.PROPERTY,dir.toString());
        try {
            Class<?> type=Class.forName(prefix,true,isolated);
            type.getMethod("mark",String.class).invoke(null,"trace-start");
            assertTrue((Boolean)type.getField("ENABLED").get(null));
            try(DirectoryStream<Path> files=Files.newDirectoryStream(dir,"lod-*.json")) {
                assertTrue(files.iterator().hasNext());
            }
        } finally {
            if(previous==null)System.clearProperty(LabLodTrace.PROPERTY);else System.setProperty(LabLodTrace.PROPERTY,previous);
        }
    }

    @Test public void streamsRowsAndKeepsASummaryCurrent() throws Exception {
        Path dir=Files.createTempDirectory("lod-trace");
        LabLodTrace trace=new LabLodTrace(dir,"test");
        JsonObject opened=new JsonParser().parse(new String(Files.readAllBytes(dir.resolve("lod-test.json")),StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(opened.get("closed").getAsBoolean());
        long pos=LabLodTrace.encode(8,-3,5);
        trace.add("section-new",pos,0,0,0,0);trace.add("upload-done",pos,1,0,0,0);trace.add("tree",0,4,3,2,1);
        trace.close();
        List<String> lines=Files.readAllLines(dir.resolve("lod-test.csv"),StandardCharsets.UTF_8);
        assertEquals("nanos,thread,op,pos,detail,x,z,a,b,c,d",lines.get(0));
        assertEquals(4,lines.size());
        String[] row=lines.get(1).split(",");
        assertEquals("section-new",row[2]);assertEquals(Long.toString(pos),row[3]);assertEquals("8",row[4]);assertEquals("-3",row[5]);assertEquals("5",row[6]);
        assertTrue(lines.get(3).endsWith(",tree,0,0,0,0,4,3,2,1"));
        JsonObject summary=new JsonParser().parse(new String(Files.readAllBytes(dir.resolve("lod-test.json")),StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(summary.get("closed").getAsBoolean());
        assertEquals(3,summary.get("written").getAsLong());assertEquals(0,summary.get("dropped").getAsLong());
        assertEquals(1,summary.getAsJsonObject("operations").get("upload-done").getAsLong());
        assertTrue(summary.getAsJsonObject("threads").size()>=1);
        trace.add("late",pos,0,0,0,0);trace.flush();
        assertEquals(4,Files.readAllLines(dir.resolve("lod-test.csv"),StandardCharsets.UTF_8).size());
    }
}
