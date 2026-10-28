import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Java 26 diagnostic caller: filter binary JFR before expanding repeated stack metadata. */
class JfrWindowExport {
    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
    static String method(RecordedMethod method) {
        return "{\"name\":" + quote(method.getName()) + ",\"type\":{\"name\":" + quote(method.getType().getName()) + "}}";
    }
    static String thread(RecordedThread thread) {
        return "{\"javaName\":" + quote(thread.getJavaName()) + ",\"osName\":" + quote(thread.getOSName())
                + ",\"javaThreadId\":" + thread.getJavaThreadId() + ",\"osThreadId\":" + thread.getOSThreadId() + "}";
    }
    static long epoch(Instant time) { return Math.addExact(Math.multiplyExact(time.getEpochSecond(), 1_000_000_000L), time.getNano()); }

    public static void main(String[] args) throws Exception {
        long minimum = Long.parseLong(args[1]), maximum = Long.parseLong(args[2]);
        Set<String> wanted = Set.of(args[3].split(","));
        Map<String, Long> fullCounts = new TreeMap<>();
        long firstEvent = Long.MAX_VALUE, lastEvent = Long.MIN_VALUE, recordedEvents = 0, exportedEvents = 0, dataLossEvents = 0;
        long exportLimit = args.length > 6 ? Long.parseLong(args[6]) : Long.MAX_VALUE;
        System.out.print("{\"recording\":{\"events\":[");
        boolean first = true;
        try (RecordingFile input = new RecordingFile(Path.of(args[0]))) {
            while (input.hasMoreEvents()) {
                RecordedEvent event = input.readEvent();
                String kind = event.getEventType().getName();
                long start = epoch(event.getStartTime()), end = epoch(event.getEndTime());
                firstEvent = Math.min(firstEvent, start);
                lastEvent = Math.max(lastEvent, end);
                recordedEvents++;
                if (kind.equals("jdk.DataLoss")) dataLossEvents++;
                if (!wanted.contains(kind)) continue;
                RecordedMethod traced = kind.equals("jdk.MethodTrace") ? event.getValue("method") : null;
                if (traced != null) {
                    RecordedThread owner = event.getThread();
                    String key = (owner == null ? "(unknown)" : owner.getJavaName()) + " | " + traced.getType().getName() + "." + traced.getName();
                    fullCounts.merge(key, 1L, Long::sum);
                }
                if (!(start >= minimum && start < maximum || start < maximum && end > minimum)) continue;
                if (++exportedEvents > exportLimit) throw new IllegalStateException("JFR export event limit exceeded");
                StringBuilder v = new StringBuilder("{\"startTime\":").append(quote(event.getStartTime().toString()));
                if (event.hasField("duration")) v.append(",\"duration\":").append(quote(event.getDuration().toString()));
                for (String field : List.of("eventThread", "sampledThread")) {
                    if (event.hasField(field)) {
                        RecordedThread owner = event.getThread(field);
                        if (owner != null) v.append(',').append(quote(field)).append(':').append(thread(owner));
                    }
                }
                for (String field : List.of("weight", "bytesRead", "bytesWritten", "amount", "total"))
                    if (event.hasField(field)) v.append(',').append(quote(field)).append(':').append(event.getLong(field));
                for (String field : List.of("sumOfPauses", "longestPause"))
                    if (event.hasField(field)) v.append(',').append(quote(field)).append(':').append(quote(event.getDuration(field).toString()));
                for (String field : List.of("cause", "name"))
                    if (event.hasField(field)) v.append(',').append(quote(field)).append(':').append(quote(event.getString(field)));
                if (event.hasField("objectClass")) {
                    RecordedClass cls = event.getClass("objectClass");
                    if (cls != null) v.append(",\"objectClass\":{\"name\":").append(quote(cls.getName())).append('}');
                }
                if (traced != null) v.append(",\"method\":").append(method(traced));
                RecordedStackTrace stack = event.getStackTrace();
                if (stack != null) {
                    int limit = traced == null ? stack.getFrames().size() : Math.min(1, stack.getFrames().size());
                    v.append(",\"stackTrace\":{\"truncated\":").append(stack.isTruncated() || limit < stack.getFrames().size()).append(",\"frames\":[");
                    for (int i = 0; i < limit; i++) {
                        if (i > 0) v.append(',');
                        v.append("{\"method\":").append(method(stack.getFrames().get(i).getMethod())).append('}');
                    }
                    v.append("]}");
                }
                v.append('}');
                if (!first) System.out.print(',');
                System.out.print("{\"type\":" + quote(kind) + ",\"values\":" + v + "}");
                first = false;
            }
        }
        System.out.println("]}}");
        StringJoiner counts = new StringJoiner(",", "{", "}\n");
        fullCounts.forEach((key, count) -> counts.add(quote(key) + ":" + count));
        Files.writeString(Path.of(args[4]), counts.toString());
        if (args.length > 5) {
            Files.writeString(Path.of(args[5]), "{\"recordedEvents\":" + recordedEvents
                    + ",\"firstEventEpochNanos\":" + (recordedEvents == 0 ? "null" : firstEvent)
                    + ",\"lastEventEpochNanos\":" + (recordedEvents == 0 ? "null" : lastEvent)
                    + ",\"dataLossEvents\":" + dataLossEvents + "}\n");
        }
    }
}
