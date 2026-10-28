package io.github.opencubicchunks.cubicchunks.lab;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorld;

/** Owner-thread sparse render-demand samples, not a stationary completion gate. */
final class LabMovingCoverage {
    private long lastSample,lastImage,frame;
    private int lastX=Integer.MIN_VALUE,lastY,lastZ,images;
    void frame(Minecraft mc,String id,boolean moving) {
        if(!LabDeliveryTrace.ENABLED||mc.player==null||mc.world==null)return;
        frame++;long now=System.nanoTime();
        int x=(int)Math.floor(mc.player.posX/16),y=(int)Math.floor(mc.player.posY/16),z=(int)Math.floor(mc.player.posZ/16);
        if(x!=lastX||y!=lastY||z!=lastZ){LabDeliveryTrace.event("client-boundary",x,y,z,frame,0);lastX=x;lastY=y;lastZ=z;}
        if(now-lastSample>=200000000L) {
            lastSample=now;
            LabRenderReadyAccess renderer=(LabRenderReadyAccess)mc.renderGlobal;
            int[] demand=renderer.cubiclab$renderDemand();
            LabDeliveryTrace.event("frame-demand",x,y,z,frame,demand[0]);
            final int[] count={0};
            renderer.cubiclab$visitOutstanding(section->{
                if(count[0]++>=4096)return;
                io.github.opencubicchunks.cubicchunks.api.world.ICube cube=
                    ((ICubicWorld)mc.world).getCubeCache().getLoadedCube(section[0],section[1],section[2]);
                int state=section[3]|(section[4]<<1)|(section[5]<<2)|(cube!=null?8:0)|(cube!=null&&cube.isEmpty()?16:0);
                LabDeliveryTrace.event("frame-outstanding",section[0],section[1],section[2],frame,state);
            });
            if(count[0]>4096)LabDeliveryTrace.event("frame-sections-dropped",x,y,z,frame,count[0]-4096);
        }
        if(moving&&images<13&&now-lastImage>=5000000000L) {
            lastImage=now;images++;
            LabDeliveryTrace.event("screenshot",x,y,z,frame,images);
            ScreenShotHelper.saveScreenshot(mc.gameDir,"cubiclab-"+id+"-moving-"+images+".png",
                    mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
        }
    }
}
