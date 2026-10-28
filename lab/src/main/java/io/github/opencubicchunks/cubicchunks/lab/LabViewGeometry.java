package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;

/** One immutable, shared server/client coordinate contract; all distance-12 positions remain required. */
final class LabViewGeometry {
    static final int DISTANCE = 12, EXPECTED = 15625;
    final double x, y, z;
    final float yaw,pitch;
    final int minX, minY, minZ, maxX, maxY, maxZ;
    LabViewGeometry(double x, double y, double z) {
        this(x,y,z,0,28);
    }
    LabViewGeometry(double x,double y,double z,float yaw,float pitch) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 29999000 || Math.abs(z) > 29999000 || y < -128 || y >= 384
                || !Float.isFinite(yaw)||!Float.isFinite(pitch)||Math.abs(yaw)>360||Math.abs(pitch)>90)
            throw new IllegalArgumentException("Invalid full-view camera or height; expected finite coordinates inside lab bounds");
        this.x=x; this.y=y; this.z=z;
        this.yaw=yaw;this.pitch=pitch;
        int cx=(int)Math.floor(x/16), cy=(int)Math.floor(y/16), cz=(int)Math.floor(z/16);
        minX=cx-DISTANCE; maxX=cx+DISTANCE; minY=cy-DISTANCE; maxY=cy+DISTANCE; minZ=cz-DISTANCE; maxZ=cz+DISTANCE;
    }
    static LabViewGeometry configured() {
        return new LabViewGeometry(Double.parseDouble(System.getProperty("cubiclab.viewX", "10112.5")),
                Double.parseDouble(System.getProperty("cubiclab.viewY", "288")),
                Double.parseDouble(System.getProperty("cubiclab.viewZ", "4256.5")),
                Float.parseFloat(System.getProperty("cubiclab.viewYaw","0")),Float.parseFloat(System.getProperty("cubiclab.viewPitch","28")));
    }
    boolean matchesPose(double actualX,double actualY,double actualZ,float actualYaw,float actualPitch) {
        return Math.abs(actualX-x)<=.3&&Math.abs(actualY-y)<=.3&&Math.abs(actualZ-z)<=.3
                &&Math.abs(Math.IEEEremainder((double)actualYaw-yaw,360))<=.1
                &&Math.abs(actualPitch-pitch)<=.1;
    }
    static LabViewGeometry read(JsonObject data) {
        if (data.get("expected").getAsInt()!=EXPECTED || data.get("distance").getAsInt()!=DISTANCE)
            throw new IllegalArgumentException("Full-view contract requires all 15625 positions at distance 12");
        return new LabViewGeometry(data.get("x").getAsDouble(), data.get("y").getAsDouble(), data.get("z").getAsDouble(),
                data.has("yaw")?data.get("yaw").getAsFloat():0,data.has("pitch")?data.get("pitch").getAsFloat():28);
    }
    JsonObject json() {
        JsonObject data=new JsonObject(); data.addProperty("x",x); data.addProperty("y",y); data.addProperty("z",z);
        data.addProperty("yaw",yaw);data.addProperty("pitch",pitch);
        data.addProperty("distance",DISTANCE); data.addProperty("expected",EXPECTED); return data;
    }
}
