package io.github.opencubicchunks.cubicchunks.core.server;

import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Owner-thread load-event scope. Nested loads must preserve every enclosing column. */
final class LoadingColumnContext implements Consumer<Chunk> {
    private final ArrayList<Chunk> columns = new ArrayList<>(2);

    @Override public void accept(@Nullable Chunk column) {
        if (column == null) columns.remove(columns.size() - 1);
        else columns.add(column);
    }

    @Nullable Chunk get(int x, int z) {
        for (int i = columns.size() - 1; i >= 0; i--) {
            Chunk column = columns.get(i);
            if (column.x == x && column.z == z) return column;
        }
        return null;
    }
}
