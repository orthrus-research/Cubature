package io.github.opencubicchunks.cubicchunks.core.server;

/**
 * Marks only the periodic server save (MixinMinecraftServer). Its one reader is
 * MixinWorldServer_PeriodicAutosave, which turns that save's saveChunks call into
 * {@link CubeProviderServer#beginPeriodicAutosave()}; everything else keeps full-save semantics.
 */
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
