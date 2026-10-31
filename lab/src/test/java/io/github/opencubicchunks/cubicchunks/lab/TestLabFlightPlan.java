package io.github.opencubicchunks.cubicchunks.lab;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;
public class TestLabFlightPlan {
    private LabFlightPlan plan(){return plan(16);}
    private LabFlightPlan plan(double speed){return new LabFlightPlan(new JsonParser().parse("{\"spectatorFlight\":{\"durationSeconds\":60,\"blocksPerSecond\":"+speed+",\"settleSeconds\":20}}").getAsJsonObject());}
    @Test public void deadlineUsesWallTimeRatherThanNumberOfServerTicks(){
        LabFlightPlan p=plan();assertFalse(p.finished(100,60000000099L));assertTrue(p.finished(100,60000000100L));assertEquals(.8,p.velocityPerClientTick(),0);
        assertTrue(p.finished(Long.MAX_VALUE-10,Long.MIN_VALUE+60000000000L));
    }
    @Test public void shortRoutesAndTickTimedSlowdownsCannotPass(){
        LabFlightPlan p=plan();assertTrue(p.validOutcome(60050000000L,960));assertFalse(p.validOutcome(59000000000L,960));
        assertFalse(p.validOutcome(70000000000L,960));assertFalse(p.validOutcome(60050000000L,800));
    }
    @Test public void elytraSpeedFlightsCoverTheirOwnDistance(){
        LabFlightPlan p=plan(34);assertEquals(1.7,p.velocityPerClientTick(),1e-12);
        assertTrue(p.validOutcome(60050000000L,2040));assertFalse(p.validOutcome(60050000000L,960));
        for(double bad:new double[]{3,41,16.5})
            try{plan(bad);fail("accepted "+bad+" blocks/s");}catch(IllegalArgumentException expected){}
    }
}
