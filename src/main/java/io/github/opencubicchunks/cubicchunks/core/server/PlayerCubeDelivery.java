package io.github.opencubicchunks.cubicchunks.core.server;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;

/** Delivered players occupy the existing subscription list's prefix; no per-cube set is allocated. */
final class PlayerCubeDelivery {
    private PlayerCubeDelivery() { }

    interface Publication<C, W, P> {
        void markSent(C cube, W originalWatcher, P player);
        void notifySent(C cube, W originalWatcher, P player);
    }

    /** The full base is already enqueued: publish the entire batch before any reentrant callback. */
    static <C, W, P> void publishBatch(List<C> cubes, List<W> originalWatchers, P player, Publication<C, W, P> publication) {
        int size = cubes.size();
        if (originalWatchers.size() != size) throw new IllegalArgumentException("Misaligned cube publication batch");
        for (int i = 0; i < size; i++) publication.markSent(cubes.get(i), originalWatchers.get(i), player);
        for (int i = 0; i < size; i++) publication.notifySent(cubes.get(i), originalWatchers.get(i), player);
    }

    static <P> boolean isSent(ObjectArrayList<P> players, P player, int sent) {
        int index = players.indexOf(player);
        return index >= 0 && index < sent;
    }

    static <P> int markSent(ObjectArrayList<P> players, P player, int sent) {
        int index = players.indexOf(player);
        if (index < 0 || index < sent) return sent;
        players.set(index, players.get(sent));
        players.set(sent, player);
        return sent + 1;
    }

    static <P> int remove(ObjectArrayList<P> players, P player, int sent) {
        int index = players.indexOf(player);
        if (index < 0) return sent;
        if (index < sent) {
            --sent;
            players.set(index, players.get(sent));
            index = sent;
        }
        players.remove(index);
        return sent;
    }
}
