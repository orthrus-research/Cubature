package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Prepared lab inputs only. No generator implementation or production CC policy lives here. */
final class LabBenchmarkWorkload {
    final JsonObject data;
    final Map<String,Object> identity;
    final long seed;
    final int warmupTicks, idleTicks, residentTicks, timeoutSeconds, batchSize, asyncConcurrency;
    final int originX, originZ, width, depth, offsetX, offsetZ;
    final String requirement;
    final double[][] points;
    final int[] durations;
    final int routeTicks;
    final boolean linear;
    final boolean waitForOverlays;

    static LabBenchmarkWorkload configured() {
        String path=System.getProperty("cubiclab.workload");
        if(path==null)return new LabBenchmarkWorkload(null,null);
        try {
            Path file=Paths.get(path);
            if(Files.size(file)>65536)throw new IllegalArgumentException("Workload exceeds 64 KiB");
            byte[] bytes=Files.readAllBytes(file);
            return new LabBenchmarkWorkload(new JsonParser().parse(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject(),bytes);
        } catch(Exception error){throw new IllegalArgumentException("Cannot load prepared benchmark workload",error);}
    }
    LabBenchmarkWorkload(JsonObject input,byte[] bytes) {
        data=input;
        JsonObject root=input==null?new JsonObject():input;
        identity=input==null?null:new LinkedHashMap<String,Object>();
        waitForOverlays=input!=null&&(!root.has("waitForOverlays")||root.get("waitForOverlays").getAsBoolean());
        seed=input==null?8675309L:input.get("seed").getAsLong();
        if(input!=null){
            number(root,"schema",1,1,1);
            String id=root.get("id").getAsString();
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}"))throw new IllegalArgumentException("Invalid workload id");
            pose(root.getAsJsonArray("camera"));
            identity.put("schema",1);identity.put("id",id);identity.put("seed",seed);identity.put("sha256",digest(bytes));
        }
        warmupTicks=number(root,"warmupTicks",40,0,1200);
        idleTicks=number(root,"idleTicks",100,100,12000);
        residentTicks=number(root,"residentTicks",100,100,12000);
        timeoutSeconds=number(root,"timeoutSeconds",840,30,3600);
        JsonObject fixture=root.has("fixture")?root.getAsJsonObject("fixture"):new JsonObject();
        int[] origin=pair(fixture,"originColumn",128,128,-1873000,1873000);
        int[] size=pair(fixture,"sizeColumns",4,4,1,8),offset=pair(fixture,"cohortOffset",32,0,-1024,1024);
        originX=origin[0];originZ=origin[1];width=size[0];depth=size[1];offsetX=offset[0];offsetZ=offset[1];
        if(Math.abs(offsetX)<width&&Math.abs(offsetZ)<depth)throw new IllegalArgumentException("Fixture cohorts overlap");
        batchSize=number(fixture,"batchSize",8,1,64);asyncConcurrency=number(fixture,"asyncConcurrency",16,1,64);
        requirement=fixture.has("requirement")?fixture.get("requirement").getAsString():"GENERATE";
        if(!Arrays.asList("GENERATE","POPULATE","LIGHT").contains(requirement))throw new IllegalArgumentException("Invalid generation requirement");
        JsonObject route=root.has("route")?root.getAsJsonObject("route"):new JsonObject();
        String interpolation=route.has("interpolation")?route.get("interpolation").getAsString():"step";
        if(!Arrays.asList("step","linear").contains(interpolation))throw new IllegalArgumentException("Invalid interpolation");
        linear=interpolation.equals("linear");
        JsonArray values=route.has("waypoints")?route.getAsJsonArray("waypoints"):new JsonArray();
        if(values.size()>256||(root.has("route")&&values.size()==0))throw new IllegalArgumentException("Route requires 1..256 waypoints");
        points=new double[values.size()][];durations=new int[values.size()];int total=0;
        for(int i=0;i<points.length;i++){
            JsonObject point=values.get(i).getAsJsonObject();points[i]=pose(point.getAsJsonArray("pose"));
            durations[i]=number(point,"ticks",0,1,12000);total+=durations[i];
        }
        if(total>12000)throw new IllegalArgumentException("Route exceeds 12000 ticks");
        routeTicks=total;
    }
    int waypoint(int tick) {
        if(tick<0||tick>=routeTicks)throw new IllegalArgumentException("Route tick outside declared window");
        int i=0;while(tick>=durations[i])tick-=durations[i++];return i;
    }
    boolean moveAt(int tick) {
        if(linear||tick==0)return true;
        return waypoint(tick)!=waypoint(tick-1);
    }
    double[] position(int tick) {
        int index=waypoint(tick),local=tick;
        for(int i=0;i<index;i++)local-=durations[i];
        double[] point=points[index].clone();
        if(linear&&index+1<points.length){
            double fraction=durations[index]==1?1:(double)local/(durations[index]-1);
            for(int axis=0;axis<5;axis++){
                double delta=points[index+1][axis]-point[axis];
                if(axis==3){delta=((delta+180)%360+360)%360-180;}
                point[axis]+=fraction*delta;
            }
        }
        return point;
    }
    void verifyPeer(JsonObject advertised) {
        JsonElement expected=identity==null?JsonNull.INSTANCE:new Gson().toJsonTree(identity);
        JsonElement actual=advertised.has("benchmarkWorkload")?advertised.get("benchmarkWorkload"):JsonNull.INSTANCE;
        if(!expected.equals(actual))throw new IllegalArgumentException("Client/server workload identity differs");
    }
    private static int[] pair(JsonObject data,String name,int x,int z,int low,int high){
        if(!data.has(name))return new int[]{x,z};JsonArray pair=data.getAsJsonArray(name);
        if(pair.size()!=2)throw new IllegalArgumentException(name+" needs a pair");
        return new int[]{integer(pair.get(0),low,high),integer(pair.get(1),low,high)};
    }
    private static int number(JsonObject data,String name,int fallback,int low,int high){return data.has(name)?integer(data.get(name),low,high):fallback;}
    private static int integer(JsonElement value,int low,int high){
        double number=value.getAsDouble();
        if(!Double.isFinite(number)||number!=Math.rint(number)||number<low||number>high)throw new IllegalArgumentException("Integer workload value out of bounds");
        return (int)number;
    }
    private static double[] pose(JsonArray value){
        if(value==null||value.size()!=5)throw new IllegalArgumentException("Pose requires x,y,z,yaw,pitch");
        double[] p=new double[5];for(int i=0;i<5;i++){p[i]=value.get(i).getAsDouble();if(!Double.isFinite(p[i]))throw new IllegalArgumentException("Nonfinite pose");}
        if(Math.abs(p[0])>29999000||Math.abs(p[2])>29999000||p[1]<-128||p[1]>=384||Math.abs(p[3])>360||Math.abs(p[4])>90)throw new IllegalArgumentException("Pose outside supported bounds");
        return p;
    }
    private static String digest(byte[] bytes){
        try{StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
        catch(java.security.NoSuchAlgorithmException error){throw new AssertionError(error);}
    }
}
