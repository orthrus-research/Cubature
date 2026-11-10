package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.core.server.TickDiagnostics;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;

/** The first wall-clock minute after the dedicated server's own ready message. */
final class LabStartupTicks {
    static final long WINDOW_NANOS = 60_000_000_000L;
    static final String LOGGER = "net.minecraft.server.dedicated.DedicatedServer";
    private static final String APPENDER = "CubicLabStartupTicks";
    private final LongSupplier clock;
    private final int capacity;
    private LabTickCosts costs;
    private Thread owner;
    private boolean done, complete;
    private int doneEvents;
    private long doneNanos, observedThrough;
    private String doneUtc, doneMessage, doneThread, callbackThread, failure;
    private Path output;
    private LoggerContext context;
    private LoggerConfig logger;
    private AbstractAppender appender;

    LabStartupTicks() { this(System::nanoTime, 4096); }

    LabStartupTicks(LongSupplier clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
        costs = new LabTickCosts(capacity);
    }

    /** Called before world initialization; all log/tick callbacks must stay on this server thread. */
    void open(Path directory) {
        closeAppender();
        reset(Thread.currentThread());
        output = directory.resolve(UUID.randomUUID().toString()).resolve("startup.json");
        context = (LoggerContext) LogManager.getContext(false);
        // Minecraft's logger can disable additivity, so attaching only to Root misses its ready line.
        logger = context.getConfiguration().getLoggerConfig(LOGGER);
        if (logger.getAppenders().containsKey(APPENDER)) {
            fail("A startup observer is already attached");
            return;
        }
        appender = new AbstractAppender(APPENDER, null, null, true) {
            @Override public void append(LogEvent event) {
                if (LabStartupTicks.LOGGER.equals(event.getLoggerName()) && event.getMessage() != null) {
                    observeLog(event.getLoggerName(), event.getMessage().getFormattedMessage(),
                            event.getThreadName(), Thread.currentThread());
                }
            }
        };
        appender.start();
        logger.addAppender(appender, Level.INFO, null);
        context.updateLoggers();
    }

    synchronized void reset(Thread serverThread) {
        owner = serverThread;
        costs = new LabTickCosts(capacity);
        done = complete = false;
        doneEvents = 0;
        doneNanos = observedThrough = 0;
        doneUtc = doneMessage = doneThread = callbackThread = failure = null;
    }

    synchronized void observeLog(String loggerName, String message, String eventThread, Thread actualThread) {
        if (!LOGGER.equals(loggerName) || !message.startsWith("Done (")) return;
        doneEvents++;
        if (doneEvents != 1) { fail("More than one dedicated-server ready message"); return; }
        doneThread = eventThread;
        callbackThread = actualThread.getName();
        if (actualThread != owner || !owner.getName().equals(eventThread)) {
            fail("Ready message was not observed synchronously on the server thread");
            return;
        }
        if (!message.matches("Done \\([0-9]+(?:\\.[0-9]+)?s\\)! For help, type \"help\" or \"\\?\"")) {
            fail("Unexpected dedicated-server ready message");
            return;
        }
        doneNanos = observedThrough = clock.getAsLong();
        doneUtc = Instant.now().toString();
        doneMessage = message;
        done = true;
    }

    synchronized void record(TickDiagnostics.Sample sample) {
        if (owner == null || !done || complete) return;
        if (Thread.currentThread() != owner) { fail("Tick sample came from another thread"); return; }
        long startOffset = sample.endedNanos() - sample.elapsedNanos() - doneNanos;
        if (startOffset >= 0 && startOffset < WINDOW_NANOS) costs.record(sample);
        observedThrough = sample.endedNanos();
        // Keep the whole duration of a tick that starts inside and ends beyond the boundary.
        complete = sample.endedNanos() - doneNanos >= WINDOW_NANOS;
    }

    synchronized Map<String, Object> summary() {
        Map<String, Object> ticks = costs.summary();
        String reason = failure;
        if (reason == null && !done) reason = "No dedicated-server ready message";
        if (reason == null && !complete) reason = "Server stopped before the startup window completed";
        if (reason == null && ((Number) ticks.get("ticks")).longValue() == 0) reason = "No startup ticks observed";
        if (reason == null && ((Number) ticks.get("droppedTicks")).longValue() != 0) reason = "Startup ticks exceeded the storage bound";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "cubiclab.startup-ticks.v1");
        result.put("valid", reason == null);
        result.put("complete", complete);
        result.put("failure", reason);
        result.put("doneObserved", done);
        result.put("doneEvents", doneEvents);
        result.put("doneMonotonicNanos", doneNanos);
        result.put("doneUtc", doneUtc);
        result.put("doneMessage", doneMessage);
        result.put("expectedServerThread", owner == null ? null : owner.getName());
        result.put("observedDoneThread", doneThread);
        result.put("observedCallbackThread", callbackThread);
        result.put("observedThroughMonotonicNanos", observedThrough);
        result.put("windowNanos", WINDOW_NANOS);
        result.put("startPolicy", "Tick starts in [Done, Done + 60 seconds); crossing ticks retain their entire duration");
        result.put("tickCosts", ticks);
        result.put("scope", "Ready time is the synchronous log-appender observation of the dedicated server's actual Done message. Tick durations use Cubature's complete owner-thread envelope; ordinary post-join scenario timers are separate.");
        return result;
    }

    /** Shutdown runs outside the measured tick; no file work occurs at the window boundary. */
    void finish() { finish(null); }

    void finish(Throwable primaryFailure) {
        try {
            closeAppender();
            if (output != null) LabData.write(output, summary());
        } catch (RuntimeException | Error exportFailure) {
            if (primaryFailure == null) throw exportFailure;
            primaryFailure.addSuppressed(exportFailure);
        } finally {
            output = null;
        }
    }

    private synchronized void fail(String reason) { if (failure == null) failure = reason; }

    private void closeAppender() {
        if (appender == null) return;
        logger.removeAppender(APPENDER);
        appender.stop();
        context.updateLoggers();
        appender = null;
    }
}
