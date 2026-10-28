package io.github.opencubicchunks.cubicchunks.core.network;

import javax.annotation.Nullable;

/** Optional owner-installed observations. No listener, clocks or packet hashing are needed in ordinary runs. */
public final class CubeSendDiagnostics {
    @Nullable private static volatile Observer observer;

    private CubeSendDiagnostics() { }

    @Nullable public static Observer getObserver() { return observer; }

    @Nullable public static Observer setObserver(@Nullable Observer next) {
        Observer previous = observer;
        observer = next;
        return previous;
    }

    /** Callbacks must be bounded, nonblocking and must not mutate packets or world state. */
    public interface Observer {
        void onSnapshot(PacketCubes packet, long startedNanos, long finishedNanos);
        void onQueued(PacketCubes packet, long queuedNanos);
        void onApply(PacketCubes packet, long queuedNanos, long startedNanos, long finishedNanos, boolean completed);
        void onSendStage(long startedNanos, long finishedNanos, long lightingNanos, int cubes, long bodyBytes,
                         int pendingCubes, long oldestPendingAgeNanos);
    }
}
