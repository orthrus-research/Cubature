package io.github.opencubicchunks.cubicchunks.lab;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lab-owned stage boundaries; samples overlap enclosing tick/view timers and are never added to them. */
final class LabStageTimings {
    private final Map<String, Samples> stages = new LinkedHashMap<>();
    void record(String stage, long started) {
        long end = System.nanoTime();
        Samples samples = stages.get(stage);
        if (samples == null) { samples = new Samples(4096); stages.put(stage, samples); }
        samples.addAt(end - started, end);
    }
    Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Samples> item : stages.entrySet()) result.put(item.getKey(), item.getValue().summary());
        result.put("scope", "Actual lab stage durations, including qualification witnesses/save/flush; overlap enclosing tick and scenario time. Preflight stages occur before capture and are labeled separately.");
        return result;
    }
}
