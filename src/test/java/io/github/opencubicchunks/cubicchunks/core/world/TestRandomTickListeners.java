package io.github.opencubicchunks.cubicchunks.core.world;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicRandomTickListener;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TestRandomTickListeners {
    private static final ICube CUBE = (ICube) Proxy.newProxyInstance(ICube.class.getClassLoader(), new Class<?>[] {ICube.class},
            (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });

    private static final class Recording implements ICubicRandomTickListener {
        final String name; final boolean admit, tick; final List<String> calls;
        Recording(String name, boolean admit, boolean tick, List<String> calls) {
            this.name = name; this.admit = admit; this.tick = tick; this.calls = calls;
        }
        @Override public boolean admitCube(World world, ICube cube) { calls.add(name + ":admit"); return admit; }
        @Override public boolean onDraw(World world, ICube cube, BlockPos pos, IBlockState state) {
            calls.add(name + ":draw " + pos.getX() + "," + pos.getY() + "," + pos.getZ()); return tick;
        }
    }

    @Test public void noListenersAdmitAndTick() {
        RandomTickListeners listeners = new RandomTickListeners();
        assertEquals(0, listeners.current().length);
        assertTrue(RandomTickListeners.admit(listeners.current(), null, CUBE));
        assertTrue(RandomTickListeners.draw(listeners.current(), null, CUBE, BlockPos.ORIGIN, null));
    }

    @Test public void everyListenerIsAskedInOrderAndAnyRefusalWins() {
        List<String> calls = new ArrayList<>();
        RandomTickListeners listeners = new RandomTickListeners();
        listeners.add(new Recording("a", false, true, calls));
        listeners.add(new Recording("b", true, false, calls));
        assertFalse(RandomTickListeners.admit(listeners.current(), null, CUBE));
        assertFalse(RandomTickListeners.draw(listeners.current(), null, CUBE, new BlockPos(-17, 4096, 3), null));
        assertEquals(List.of("a:admit", "b:admit", "a:draw -17,4096,3", "b:draw -17,4096,3"), calls);
    }

    @Test public void aPassKeepsTheArrayItReadWhileRegistrationChanges() {
        List<String> calls = new ArrayList<>();
        RandomTickListeners listeners = new RandomTickListeners();
        Recording a = new Recording("a", true, true, calls);
        listeners.add(a);
        ICubicRandomTickListener[] pass = listeners.current();
        listeners.add(new Recording("b", true, true, calls));
        assertTrue(listeners.remove(a));
        RandomTickListeners.admit(pass, null, CUBE);
        assertEquals(List.of("a:admit"), calls);
        assertEquals(1, listeners.current().length);
        assertEquals(1, pass.length);
    }

    @Test public void duplicatesAndNullsAreRejectedAndUnknownRemovalsReportFalse() {
        RandomTickListeners listeners = new RandomTickListeners();
        Recording a = new Recording("a", true, true, new ArrayList<>());
        listeners.add(a);
        assertThrows(IllegalArgumentException.class, () -> listeners.add(a));
        assertThrows(NullPointerException.class, () -> listeners.add(null));
        assertFalse(listeners.remove(new Recording("b", true, true, new ArrayList<>())));
        assertTrue(listeners.remove(a));
        assertFalse(listeners.remove(a));
        assertEquals(0, listeners.current().length);
    }
}
