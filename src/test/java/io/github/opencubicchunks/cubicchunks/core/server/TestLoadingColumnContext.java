package io.github.opencubicchunks.cubicchunks.core.server;

import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.Chunk;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestLoadingColumnContext {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void unrelatedCoordinatesNeverAliasAnInFlightColumn() {
        LoadingColumnContext context = new LoadingColumnContext();
        assertNull(context.get(10, 20));
        Chunk column = new Chunk(null, 10, 20);
        context.accept(column);
        assertSame(column, context.get(10, 20));
        assertNull(context.get(-9, 22));
        context.accept(null);
        assertNull(context.get(10, 20));
    }

    @Test public void nestedLoadCanResolveItsParentAndRestoresItAfterFailure() {
        LoadingColumnContext context = new LoadingColumnContext();
        Chunk outer = new Chunk(null, 10, 20), inner = new Chunk(null, -9, 22);
        context.accept(outer);
        try {
            context.accept(inner);
            try {
                assertSame(outer, context.get(10, 20));
                assertSame(inner, context.get(-9, 22));
                throw new IllegalStateException("load event failure");
            } finally { context.accept(null); }
        } catch (IllegalStateException expected) {
            assertSame(outer, context.get(10, 20));
            assertNull(context.get(-9, 22));
        } finally { context.accept(null); }
        assertNull(context.get(10, 20));
    }
}
