package io.github.opencubicchunks.cubicchunks.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.opencubicchunks.cubicchunks.api.worldgen.WorldDerivedDataDirectory;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOError;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestDerivedDataDirectory {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cacheIsANormalizedAbsoluteSiblingOfTheSave() {
        Path save = temporary.getRoot().toPath().resolve("worlds/unused/../world");
        Path normalized = save.toAbsolutePath().normalize();
        Path cache = DerivedDataDirectory.resolve(save);

        assertEquals(normalized.resolveSibling("world.cubature-cache"), cache);
        assertTrue(cache.isAbsolute());
        assertEquals(cache.normalize(), cache);
        assertFalse(cache.startsWith(normalized));
    }

    @Test public void aliasesOfTheSameSaveShareOneRoot() {
        Path root = temporary.getRoot().toPath();
        Path cache = DerivedDataDirectory.resolve(root.resolve("world"));

        assertEquals(cache, DerivedDataDirectory.resolve(root.resolve("./world")));
        assertEquals(cache, DerivedDataDirectory.resolve(root.resolve("unused/../world")));
    }

    @Test public void distinctSavesKeepDistinctRoots() {
        Path root = temporary.getRoot().toPath();
        Path first = DerivedDataDirectory.resolve(root.resolve("first/world"));
        Path second = DerivedDataDirectory.resolve(root.resolve("second/world"));
        Path renamed = DerivedDataDirectory.resolve(root.resolve("first/other"));

        assertNotEquals(first, second);
        assertNotEquals(first, renamed);
    }

    @Test public void relativeSavePathsUseTheirNormalizedAbsoluteLocation() {
        Path save = Paths.get("worlds", "unused", "..", "world");
        Path expected = save.toAbsolutePath().normalize().resolveSibling("world.cubature-cache");

        assertEquals(expected, DerivedDataDirectory.resolve(save));
    }

    @Test public void missingSaveNamesLeaveDerivedDataInMemory() {
        assertNull(DerivedDataDirectory.resolve(null));
        assertNull(DerivedDataDirectory.resolve(Paths.get("")));
        assertNull(DerivedDataDirectory.resolve(temporary.getRoot().toPath().toAbsolutePath().getRoot()));
    }

    @Test public void unavailablePathResolutionLeavesDerivedDataInMemory() {
        for (Throwable failure : new Throwable[] {
                new IOError(new IOException("Unavailable working directory")),
                new SecurityException("Path resolution denied")}) {
            Path unavailable = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("toString")) return "unavailable";
                        throw failure;
                    });
            assertNull(DerivedDataDirectory.resolve(unavailable));
        }
    }

    @Test public void resolvingDoesNotCreateASaveOrACache() {
        Path save = temporary.getRoot().toPath().resolve("missing/save");
        Path cache = DerivedDataDirectory.resolve(save);

        assertFalse(Files.exists(save.getParent()));
        assertFalse(Files.exists(save));
        assertFalse(Files.exists(cache.getParent()));
        assertFalse(Files.exists(cache));
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test public void earlyRequestsAreNotWorldLifecycleEvents() {
        assertSame(Event.class, WorldDerivedDataDirectory.Request.class.getSuperclass());
        assertNull(WorldDerivedDataDirectory.request(null));
    }
}
