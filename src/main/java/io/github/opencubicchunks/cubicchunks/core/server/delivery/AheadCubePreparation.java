package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer;
import io.github.opencubicchunks.cubicchunks.core.util.ticket.ITicket;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.github.opencubicchunks.cubicchunks.core.world.cube.BlankCube;
import net.minecraft.entity.player.EntityPlayerMP;
import java.util.*;

/** Owner-thread speculation only. Never publishes extra client cubes or changes the mandatory view. */
public final class AheadCubePreparation implements ITicket {
    private final Map<CubePos,Cube> held = new HashMap<>();
    private final ArrayDeque<CubePos> queue = new ArrayDeque<>();
    private double lastX,lastZ;
    private long movedAt;
    private int cx=Integer.MIN_VALUE,cy,cz,dx,dz,h,v;
    public AheadCubePreparation(EntityPlayerMP player) { lastX=player.posX;lastZ=player.posZ; }
    @Override public boolean shouldTick() { return false; }
    public void update(EntityPlayerMP player,int horizontal,int vertical,long tick) {
        double mx=player.posX-lastX,mz=player.posZ-lastZ;lastX=player.posX;lastZ=player.posZ;
        int nextDx=dx,nextDz=dz;
        if(Math.max(Math.abs(mx),Math.abs(mz))>.01) {
            movedAt=tick;nextDx=Math.abs(mx)>=Math.abs(mz)?(mx>0?1:-1):0;
            nextDz=nextDx==0?(mz>0?1:-1):0;
        } else if(tick-movedAt>=20) { close();return; }
        int x=(int)Math.floor(player.posX/16),y=(int)Math.floor(player.posY/16),z=(int)Math.floor(player.posZ/16);
        if(x==cx&&y==cy&&z==cz&&dx==nextDx&&dz==nextDz&&h==horizontal&&v==vertical)return;
        cx=x;cy=y;cz=z;dx=nextDx;dz=nextDz;h=horizontal;v=vertical;
        List<CubePos> desired=LeadingFace.positions(x,y,z,h,v,dx,dz);
        Set<CubePos> wanted=new HashSet<>(desired);
        Iterator<Map.Entry<CubePos,Cube>> old=held.entrySet().iterator();
        while(old.hasNext()){Map.Entry<CubePos,Cube> e=old.next();if(!wanted.contains(e.getKey())){e.getValue().getTickets().remove(this);old.remove();}}
        queue.clear();
        for(CubePos pos:desired)if(!held.containsKey(pos))queue.addLast(pos);
    }
    public void service(CubeProviderServer provider,GenerationBudget mandatory,int quota) {
        int used=0;
        // Use only the remainder of the mandatory generation deadline. An independent small
        // time slice cannot prepare a full leading face at the measured 625-cube/s demand.
        while(!queue.isEmpty()&&used<quota
                &&mandatory.canGenerateCube(System.nanoTime())) {
            CubePos pos=queue.removeFirst();
            Cube cube=provider.getCubeForDelivery(pos.getX(),pos.getY(),pos.getZ(),ICubeProviderServer.Requirement.LIGHT);
            used++;mandatory.generatedCube();
            if(cube!=null&&!(cube instanceof BlankCube)){cube.getTickets().add(this);held.put(pos,cube);}
        }
    }
    public void close() {
        for(Cube cube:held.values())cube.getTickets().remove(this);
        held.clear();queue.clear();cx=Integer.MIN_VALUE;dx=dz=0;
    }
}
