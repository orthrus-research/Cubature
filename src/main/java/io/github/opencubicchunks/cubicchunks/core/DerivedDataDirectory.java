package io.github.opencubicchunks.cubicchunks.core;

import java.io.IOError;
import java.nio.file.Path;

import javax.annotation.Nullable;

/** Resolves a host-owned root beside the save without opening or creating cache files. */
final class DerivedDataDirectory {
    private DerivedDataDirectory() { }

    @Nullable static Path resolve(@Nullable Path saveDirectory) {
        if (saveDirectory == null || saveDirectory.toString().isEmpty()) return null;
        try {
            Path save = saveDirectory.toAbsolutePath().normalize();
            Path name = save.getFileName(), parent = save.getParent();
            if (name == null || parent == null) return null;
            return parent.resolve(name + ".cubature-cache");
        } catch (IOError | SecurityException unavailable) {
            return null;
        }
    }
}
