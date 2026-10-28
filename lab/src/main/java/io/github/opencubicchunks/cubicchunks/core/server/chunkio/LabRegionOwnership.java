package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import io.github.opencubicchunks.cubicchunks.core.server.CubeProviderServer;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.util.*;

/** Diagnostic-only observation of exact owned channels; never mutates cache or channel state. */
public final class LabRegionOwnership {
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    public static List<FileChannel> channels(CubeProviderServer provider) throws ReflectiveOperationException {
        AsyncBatchingCubeIO io = (AsyncBatchingCubeIO) provider.getCubeIO();
        Object cache = field(io.storage, "regions");
        List<FileChannel> channels = new ArrayList<>();
        synchronized (field(cache, "metadata")) {
            for (Object handle : ((Map<?, ?>) field(cache, "handles")).values()) {
                Object region = field(handle, "region");
                if (region == null || region instanceof cubicchunks.regionlib.lib.ExtRegion) continue;
                channels.add((FileChannel) field(region, "file"));
            }
        }
        if (channels.isEmpty()) throw new IllegalStateException("No owned file channels observed");
        return channels;
    }
}
