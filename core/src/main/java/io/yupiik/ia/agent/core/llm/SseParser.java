package io.yupiik.ia.agent.core.llm;

import java.util.ArrayList;
import java.util.List;

public final class SseParser {
    private final StringBuilder data = new StringBuilder();
    private String event;

    public List<Event> feed(final String line) {
        final var out = new ArrayList<Event>();
        if (line == null) {
            out.addAll(dispatch());
            return out;
        }
        if (line.isEmpty()) {
            out.addAll(dispatch());
            return out;
        }
        if (line.startsWith(":")) {
            return out;
        }
        if (line.startsWith("event:")) {
            event = value(line, "event:".length());
            return out;
        }
        if (line.startsWith("data:")) {
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value(line, "data:".length()));
        }
        return out;
    }

    private List<Event> dispatch() {
        final var payload = data.toString();
        data.setLength(0);
        final var type = event;
        event = null;
        if (payload.isBlank()) {
            return List.of();
        }
        return List.of(new Event(type, payload));
    }

    private static String value(final String line, final int offset) {
        var v = line.substring(Math.min(offset, line.length()));
        if (v.startsWith(" ")) {
            v = v.substring(1);
        }
        return v;
    }

    public record Event(String event, String data) {
    }
}
