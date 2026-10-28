package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class LabData {
    private LabData() { }
    public static String measurementMode() {
        if (Boolean.getBoolean("cubiclab.storageDiagnostics")) return "diagnostic-storage-profile";
        boolean render = Boolean.getBoolean("cubiclab.renderDiagnostics");
        boolean stages = Boolean.getBoolean("cubiclab.diagnostics");
        if (render && stages) return "diagnostic-renderer-and-stage-observer";
        if (render) return "diagnostic-renderer-profile";
        if (stages) return "diagnostic-stage-observer";
        if ("orphan".equals(System.getProperty("cubiclab.uploadStrategy"))) return "experimental-orphan-upload";
        return "ordinary-profile-with-bounded-lab-counters";
    }
    public static void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            String fileName = path.getFileName().toString();
            Samples.exportSummaries(path.getParent(), fileName.replaceFirst("\\.json$", ""), value);
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(value, writer);
            }
        } catch (IOException error) { throw new IllegalStateException("Cannot preserve lab result " + path, error); }
    }
    public static long[] gc() {
        long count = 0, milliseconds = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount()); milliseconds += Math.max(0, bean.getCollectionTime());
        }
        return new long[] {count, milliseconds};
    }
    public static Map<String, Object> runtime() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("java", System.getProperty("java.version")); result.put("os", System.getProperty("os.name"));
        result.put("arch", System.getProperty("os.arch")); result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        List<String> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            collectors.add(bean.getName());
        }
        result.put("garbageCollectors", collectors);
        return result;
    }
}
