package io.github.opencubicchunks.cubicchunks.lab;

import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in equivalent full-buffer upload: same bound VBO, byte window, usage and vanilla vertex-count update. */
public final class LabUploadExperiment {
    private static final String STRATEGY = System.getProperty("cubiclab.uploadStrategy", "original");
    private static long attempted, applied, fallback, bytes;
    interface Backend {
        boolean supported();
        void allocate(int target, int size, int usage);
        void write(int target, ByteBuffer data);
    }
    private static final Backend GL = new Backend() {
        public boolean supported() { return LabGlCapabilities.openGl15(); }
        public void allocate(int target, int size, int usage) { GL15.glBufferData(target, (long) size, usage); }
        public void write(int target, ByteBuffer data) { GL15.glBufferSubData(target, 0L, data); }
    };
    private LabUploadExperiment() { }

    public static boolean selected() { return "orphan".equals(STRATEGY); }

    public static boolean upload(int target, ByteBuffer data, int usage) {
        if (!selected()) return false;
        return upload(GL, target, data, usage);
    }

    static boolean upload(Backend backend, int target, ByteBuffer data, int usage) {
        attempted++;
        if (!backend.supported() || target != GL15.GL_ARRAY_BUFFER) { fallback++; return false; }
        int size = data.remaining();
        backend.allocate(target, size, usage);
        backend.write(target, data);
        applied++; bytes += size;
        return true;
    }

    static long[] snapshot() { return new long[] {attempted, applied, fallback, bytes}; }

    static Map<String, Object> summary(long[] start) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", STRATEGY);
        result.put("attempts", attempted - start[0]); result.put("applied", applied - start[1]);
        result.put("capabilityFallbacks", fallback - start[2]); result.put("uploadedBytes", bytes - start[3]);
        result.put("scope", "Isolated lab redirect; orphan then subdata for full original remaining ByteBuffer. Original binding, usage, vertex count and unsupported-capability fallback preserved. No geometry is dropped.");
        return result;
    }
}
