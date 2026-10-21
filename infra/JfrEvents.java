import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import jdk.jfr.Timespan;
import jdk.jfr.Timestamp;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * A flight recording's events as the document {@code jfr print --json} writes, read with the JDK's RecordingFile and
 * holding only what the studies' attribution reads (infra/representation_study.py jfr_document):
 * <pre>{"recording": {"events": [{"type": ..., "values": {field: value, ...}}, ...]}}</pre>
 * <p>{@code jfr print} spells out every frame's method, class, class loader, package and module in full, so a 2 MB
 * recording printed some 1.6 GB of JSON (stack verification of 2026-10-21). Here a thread is its {@code javaName}, a class
 * its {@code name} (dotted where jfr print writes slashes; the attribution reads both), a stack its frames' {@code method.type.name}, {@code method.name} and {@code lineNumber} (the first
 * {@code depth} frames, leaf first, as {@code --stack-depth} keeps them), a timestamp an ISO instant and a timespan an
 * ISO duration (what jfr prints). Numbers, strings and booleans are kept; any other nested value is left out.
 * <pre>java -cp &lt;classes&gt; JfrEvents &lt;depth&gt; &lt;event,event,...&gt; &lt;recording.jfr&gt;</pre>
 */
public final class JfrEvents {
    private JfrEvents() { }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 3) throw new IllegalArgumentException("usage: JfrEvents <depth> <event,event,...> <recording.jfr>");
        int depth = Integer.parseInt(arguments[0]);
        if (depth < 1) throw new IllegalArgumentException("depth must be at least 1");
        Set<String> types = Set.of(arguments[1].split(","));
        try (RecordingFile recording = new RecordingFile(Path.of(arguments[2]));
             Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1 << 16)) {
            out.write("{\"recording\":{\"events\":[");
            boolean first = true;
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (!types.contains(event.getEventType().getName())) continue;
                if (!first) out.write(',');
                first = false;
                out.write("{\"type\":");
                string(out, event.getEventType().getName());
                out.write(",\"values\":");
                fields(out, event, depth);
                out.write('}');
            }
            out.write("]}}\n");
        }
    }

    private static void fields(Writer out, RecordedObject object, int depth) throws IOException {
        out.write('{');
        boolean first = true;
        for (ValueDescriptor field : object.getFields()) {
            if (field.isArray()) continue;
            String name = field.getName();
            Object value = object.getValue(name);
            String written;
            if (value == null) written = "null";
            // A field can carry several content types (sumOfPauses is Unsigned and Timespan), so the annotation is asked.
            else if (field.getAnnotation(Timestamp.class) != null) written = quoted(object.getInstant(name));
            else if (field.getAnnotation(Timespan.class) != null) written = quoted(object.getDuration(name));
            else if (value instanceof RecordedThread thread) written = "{\"javaName\":" + quoted(thread.getJavaName()) + "}";
            else if (value instanceof RecordedClass type) written = "{\"name\":" + quoted(type.getName()) + "}";
            else if (value instanceof RecordedStackTrace stack) written = stack(stack, depth);
            else if (value instanceof RecordedObject) continue;
            else if (value instanceof Double || value instanceof Float) {
                double number = ((Number) value).doubleValue();
                written = Double.isFinite(number) ? Double.toString(number) : "null";
            } else if (value instanceof Number || value instanceof Boolean) written = value.toString();
            else if (value instanceof Character character) written = quoted(character.toString());
            else written = quoted(value.toString());
            if (!first) out.write(',');
            first = false;
            string(out, name);
            out.write(':');
            out.write(written);
        }
        out.write('}');
    }

    private static String stack(RecordedStackTrace stack, int depth) {
        List<RecordedFrame> frames = stack.getFrames();
        StringBuilder text = new StringBuilder(64 * Math.min(depth, frames.size()));
        text.append("{\"truncated\":").append(stack.isTruncated() || frames.size() > depth).append(",\"frames\":[");
        for (int index = 0; index < Math.min(depth, frames.size()); index++) {
            RecordedFrame frame = frames.get(index);
            if (index > 0) text.append(',');
            text.append("{\"method\":{\"type\":{\"name\":").append(quoted(frame.getMethod().getType().getName()))
                    .append("},\"name\":").append(quoted(frame.getMethod().getName()))
                    .append("},\"lineNumber\":").append(frame.getLineNumber()).append('}');
        }
        return text.append("]}").toString();
    }

    private static String quoted(Object value) {
        if (value == null) return "null";
        StringBuilder text = new StringBuilder();
        escape(text, value.toString());
        return text.toString();
    }

    private static void string(Writer out, String value) throws IOException {
        StringBuilder text = new StringBuilder();
        escape(text, value);
        out.write(text.toString());
    }

    private static void escape(StringBuilder text, String value) {
        text.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (c < 0x20) text.append(String.format("\\u%04x", (int) c));
                    else text.append(c);
                }
            }
        }
        text.append('"');
    }
}
