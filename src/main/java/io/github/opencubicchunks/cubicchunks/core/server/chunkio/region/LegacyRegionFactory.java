package io.github.opencubicchunks.cubicchunks.core.server.chunkio.region;

import cubicchunks.regionlib.api.region.key.IKey;
import cubicchunks.regionlib.api.region.key.IKeyProvider;
import cubicchunks.regionlib.api.region.key.RegionKey;
import cubicchunks.regionlib.lib.Region;
import cubicchunks.regionlib.lib.RegionSectorTracker;
import cubicchunks.regionlib.lib.header.IntPackedSectorMap;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static java.nio.file.StandardOpenOption.*;

/**
 * Keeps the pinned RegionLib legacy format/allocator, with explicit channel ownership on open failure.
 * RegionLib's builder neither accepts a channel nor closes it when header loading fails. Its private
 * constructor is the only adapter boundary; resolve it before opening any file and fail closed on drift.
 */
public final class LegacyRegionFactory {
    private LegacyRegionFactory() { }

    @SuppressWarnings("unchecked")
    public static <K extends IKey<K>> Region<K> open(Path directory, IKeyProvider<K> keys, RegionKey key) throws IOException {
        Constructor<?> constructor;
        try {
            constructor = Region.class.getDeclaredConstructor(FileChannel.class, IntPackedSectorMap.class,
                    RegionSectorTracker.class, List.class, RegionKey.class, IKeyProvider.class, int.class);
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("Unsupported RegionLib legacy constructor", error);
        }
        FileChannel file = FileChannel.open(directory.resolve(key.getName()), CREATE, READ, WRITE);
        try {
            int count = keys.getKeyCount(key);
            IntPackedSectorMap<K> map = IntPackedSectorMap.readOrCreate(file, count, Collections.emptyList());
            RegionSectorTracker<K> tracker = RegionSectorTracker.fromFile(file, map, (count * Integer.BYTES + 511) / 512, 512);
            return (Region<K>) constructor.newInstance(file, map, tracker,
                    Collections.singletonList(map.headerEntryProvider()), key, keys, 512);
        } catch (IOException | ReflectiveOperationException | RuntimeException | Error error) {
            Throwable failure = error instanceof InvocationTargetException ? error.getCause() : error;
            try { file.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IOException("Cannot open legacy region " + key.getName(), failure);
        }
    }
}
