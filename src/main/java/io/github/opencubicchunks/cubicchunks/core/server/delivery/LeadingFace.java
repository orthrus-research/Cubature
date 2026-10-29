package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import java.util.ArrayList;
import java.util.List;

/** One horizontal face, ordered near the player's height and transverse position first. */
final class LeadingFace {
    static List<CubePos> positions(int x, int y, int z, int horizontal, int vertical, int dx, int dz) {
        List<CubePos> out = new ArrayList<>();
        if (Math.abs(dx) + Math.abs(dz) != 1 || horizontal < 0 || vertical < 0
                || (2L * horizontal + 1) * (2L * vertical + 1) > 4096) return out;
        for (int a=-horizontal;a<=horizontal;a++) for(int b=-vertical;b<=vertical;b++)
            out.add(new CubePos(x+(dx==0?a:dx*(horizontal+1)),y+b,z+(dz==0?a:dz*(horizontal+1))));
        out.sort(java.util.Comparator.comparingLong(p -> {
            long transverse=dx==0?(long)p.getX()-x:(long)p.getZ()-z;
            long height=(long)p.getY()-y;
            return transverse*transverse+height*height;
        }));
        return out;
    }
}
