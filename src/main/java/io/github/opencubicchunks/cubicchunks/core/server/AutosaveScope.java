package io.github.opencubicchunks.cubicchunks.core.server;

/** Marks only the periodic server save. Explicit commands and shutdown keep full-save semantics. */
public final class AutosaveScope {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private AutosaveScope() { }
    public static boolean isActive() { return ACTIVE.get(); }
    public static void run(Runnable save) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try { save.run(); } finally { ACTIVE.set(previous); }
    }
}
