package io.github.opencubicchunks.cubicchunks.lab;

import org.junit.Test;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class TestLabStorageFaults {
    @Test public void faultRequiresMatchingPathOperationAndThread()throws Exception{
        try(LabStorageFaults.Scope scope=LabStorageFaults.arm(Paths.get("owned-fixture"),"force")){
            assertFalse(LabStorageFaults.claim(Paths.get("player-world/region"),"force"));
            assertFalse(LabStorageFaults.claim(Paths.get("owned-fixture/file"),"rename"));
            AtomicBoolean other=new AtomicBoolean(true);Thread thread=new Thread(()->other.set(LabStorageFaults.claim(Paths.get("owned-fixture/file"),"force")));thread.start();thread.join();assertFalse(other.get());
            assertTrue(LabStorageFaults.claim(Paths.get("owned-fixture/file"),"force"));
            assertFalse(LabStorageFaults.claim(Paths.get("owned-fixture/file"),"force"));assertTrue(scope.fired);
        }
        assertFalse(LabStorageFaults.claim(Paths.get("owned-fixture/file"),"force"));
    }
    @Test public void siblingPrefixCannotMatch(){
        try(LabStorageFaults.Scope ignored=LabStorageFaults.arm(Paths.get("owned"),"force")){
            assertFalse(LabStorageFaults.claim(Paths.get("owned-player/file"),"force"));
        }
    }
}
