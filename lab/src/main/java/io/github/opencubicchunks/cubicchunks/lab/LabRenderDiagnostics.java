package io.github.opencubicchunks.cubicchunks.lab;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit diagnostic lane only. Worker CPU spans, owner GL calls and deferred GPU intervals remain separate. */
public final class LabRenderDiagnostics {
    public static final int REBUILD = 0, VBO_UPLOAD = 1, DISPLAY_LIST = 2, SDL_PUMP = 3, SDL_SWAP = 4;
    private static final String[] STAGES = {"rebuildCpu", "vboUploadCpu", "displayListUploadCpu", "sdlPumpCpu", "sdlSwapCpu"};
    private static final ThreadLocal<ArrayDeque<Span>> SPANS = new ThreadLocal<>();
    private static volatile State active;
    private LabRenderDiagnostics() { }

    public static void beginCapture(String id) {
        if (active != null) throw new IllegalStateException("Previous renderer diagnostic capture is still active");
        boolean enabled = Boolean.getBoolean("cubiclab.renderDiagnostics");
        if (enabled || LabUploadExperiment.selected()) active = new State(id, enabled);
    }

    public static void frame() {
        State state = active;
        if (state != null && state.gpu != null) state.gpu.poll(++state.frame, state.gpuDurations);
    }

    public static Map<String, Object> finishCapture() {
        State state = active;
        active = null;
        if (state == null) {
            Map<String, Object> data = new LinkedHashMap<>(); data.put("enabled", false); data.put("uploadStrategy", "original");
            return data;
        }
        state.sealed = true;
        if (state.gpu != null) state.gpu.poll(state.frame, state.gpuDurations);
        if (state.gpu != null) state.gpu.close();
        return state.summary();
    }

    public static void begin(int stage) {
        State state = active;
        if (state == null || !state.enabled || state.sealed) return;
        int query = stage == VBO_UPLOAD && state.gpu != null ? state.gpu.begin(state.frame) : -1;
        ArrayDeque<Span> stack = SPANS.get();
        if (stack == null) { stack = new ArrayDeque<>(); SPANS.set(stack); }
        while (!stack.isEmpty() && stack.peek().state.sealed) stack.pop();
        state.started(stage);
        stack.push(new Span(state, stage, query, System.nanoTime()));
    }

    public static void end(int stage) {
        ArrayDeque<Span> stack = SPANS.get();
        if (stack == null || stack.isEmpty()) return;
        Span span = stack.peek();
        if (span.stage != stage) { span.state.unbalanced(); return; }
        stack.pop();
        long finished = System.nanoTime();
        if (!span.state.sealed) {
            if (span.query >= 0) span.state.gpu.end(span.query);
            span.state.finished(stage, span.started, finished);
        }
    }

    public static long enqueue(Object task) {
        State state = active;
        if (state == null || !state.enabled || state.sealed) return 0;
        long now = System.nanoTime();
        state.enqueue(task, now);
        return now;
    }

    public static void enqueueResult(Object task, long token, boolean accepted) {
        State state = active;
        if (token != 0 && state != null && !state.sealed) state.enqueueResult(task, token, accepted);
    }

    public static void workerStart(Object task) {
        State state = active;
        if (state != null && state.enabled && !state.sealed) state.workerStart(task, System.nanoTime());
    }

    private static final class Span {
        final State state;
        final int stage, query;
        final long started;
        Span(State state, int stage, int query, long started) { this.state = state; this.stage = stage; this.query = query; this.started = started; }
    }

    private static final class State {
        final String id;
        final boolean enabled;
        final long[] uploadStart = LabUploadExperiment.snapshot();
        final long[] started = new long[STAGES.length], completed = new long[STAGES.length];
        final Samples[] durations;
        final Samples queueWait, gpuDurations;
        final IdentityHashMap<Object, Long> queued;
        final DeferredGpuQueries gpu;
        final String gpuAvailability;
        volatile boolean sealed;
        long frame, accepted, rejected, queueOverflow, unmatchedWorkerStarts, unbalanced;

        State(String id, boolean enabled) {
            this.id = id; this.enabled = enabled;
            durations = enabled ? new Samples[STAGES.length] : null;
            if (enabled) for (int i = 0; i < STAGES.length; i++) durations[i] = new Samples(32768);
            queueWait = enabled ? new Samples(32768) : null;
            gpuDurations = enabled ? new Samples(32768) : null;
            queued = enabled ? new IdentityHashMap<>() : null;
            DeferredGpuQueries queries = null;
            String availability = "disabled";
            if (enabled) {
                try {
                    if (LabGlCapabilities.openGl33()) { queries = new DeferredGpuQueries(new GlQueries(), 64); availability = "OpenGL33-timestamps"; }
                    else availability = "unsupported-OpenGL33";
                } catch (RuntimeException ex) { availability = "unavailable-" + ex.getClass().getSimpleName(); }
            }
            gpu = queries; gpuAvailability = availability;
        }

        synchronized void started(int stage) { if (!sealed) started[stage]++; }
        synchronized void finished(int stage, long start, long finish) {
            if (sealed) return;
            completed[stage]++; durations[stage].addAt(finish - start, finish);
        }
        synchronized void unbalanced() { if (!sealed) unbalanced++; }
        synchronized void enqueue(Object task, long now) {
            if (sealed) return;
            if (queued.size() >= 32768 && !queued.containsKey(task)) queueOverflow++;
            else queued.put(task, now);
        }
        synchronized void enqueueResult(Object task, long token, boolean success) {
            if (sealed) return;
            if (success) accepted++;
            else { rejected++; Long value = queued.get(task); if (value != null && value.longValue() == token) queued.remove(task); }
        }
        synchronized void workerStart(Object task, long now) {
            if (sealed) return;
            Long start = queued.remove(task);
            if (start == null) unmatchedWorkerStarts++;
            else queueWait.addAt(now - start, now);
        }

        synchronized Map<String, Object> summary() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", enabled); data.put("runId", id); data.put("uploadExperiment", LabUploadExperiment.summary(uploadStart));
            if (!enabled) return data;
            for (int i = 0; i < STAGES.length; i++) {
                Map<String, Object> span = new LinkedHashMap<>(durations[i].summary());
                span.put("startsWithinWindow", started[i]); span.put("completionsWithinWindow", completed[i]);
                span.put("unfinishedOrCrossedCaptureBoundary", started[i] - completed[i]);
                data.put(STAGES[i], span);
            }
            data.put("renderTaskQueueWait", queueWait.summary()); data.put("acceptedTaskSubmissions", accepted);
            data.put("rejectedTaskSubmissions", rejected); data.put("submissionTraceOverflow", queueOverflow);
            data.put("unmatchedWorkerStarts", unmatchedWorkerStarts); data.put("unconsumedSubmissionsAtEnd", queued.size());
            data.put("unbalancedSpanReturns", unbalanced); queued.clear();
            data.put("gpuTimerAvailability", gpuAvailability);
            if (gpu != null) { data.put("vboGpuCommandIntervals", gpuDurations.summary()); data.put("gpuQueryRing", gpu.summary()); }
            data.put("sdlTargetsAvailable", LabRenderDiagnostics.class.getClassLoader().getResource("com/cleanroommc/client/sdl/Pump.class") != null);
            data.put("scope", "Diagnostic overhead is included. Rebuild/upload are method CPU spans; returns alone do not prove every visible mesh or GPU completion. Spans may overlap; do not sum them. SDL swap/pump wall time is not dedicated-server tick cost. GPU results are polled on later frames without waits.");
            return data;
        }
    }

    private static final class GlQueries implements DeferredGpuQueries.Backend {
        public int create() { return GL15.glGenQueries(); }
        public void timestamp(int query) { GL33.glQueryCounter(query, GL33.GL_TIMESTAMP); }
        public boolean available(int query) { return GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
        public long result(int query) { return GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT); }
        public void delete(int query) { GL15.glDeleteQueries(query); }
    }
}
