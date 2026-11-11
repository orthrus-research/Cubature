package io.github.opencubicchunks.cubicchunks.core.world;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicRandomTickListener;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.Objects;

/**
 * One world's random-tick listeners. Registration replaces the array, so a pass reads one array for
 * a whole cube and a listener added or removed during it takes effect from the next cube. Every
 * listener is called even after another one refuses.
 */
public final class RandomTickListeners {
    private static final ICubicRandomTickListener[] NONE = new ICubicRandomTickListener[0];
    private ICubicRandomTickListener[] listeners = NONE;

    public ICubicRandomTickListener[] current() { return listeners; }

    public void add(ICubicRandomTickListener listener) {
        Objects.requireNonNull(listener, "listener");
        for (ICubicRandomTickListener present : listeners)
            if (present == listener) throw new IllegalArgumentException("Random-tick listener already added");
        ICubicRandomTickListener[] next = Arrays.copyOf(listeners, listeners.length + 1);
        next[listeners.length] = listener;
        listeners = next;
    }

    public boolean remove(ICubicRandomTickListener listener) {
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] != listener) continue;
            ICubicRandomTickListener[] next = new ICubicRandomTickListener[listeners.length - 1];
            System.arraycopy(listeners, 0, next, 0, i);
            System.arraycopy(listeners, i + 1, next, i, next.length - i);
            listeners = next.length == 0 ? NONE : next;
            return true;
        }
        return false;
    }

    public static boolean admit(ICubicRandomTickListener[] listeners, World world, ICube cube) {
        boolean admitted = true;
        for (ICubicRandomTickListener listener : listeners) admitted &= listener.admitCube(world, cube);
        return admitted;
    }

    public static boolean draw(ICubicRandomTickListener[] listeners, World world, ICube cube, BlockPos pos, IBlockState state) {
        boolean tick = true;
        for (ICubicRandomTickListener listener : listeners) tick &= listener.onDraw(world, cube, pos, state);
        return tick;
    }
}
