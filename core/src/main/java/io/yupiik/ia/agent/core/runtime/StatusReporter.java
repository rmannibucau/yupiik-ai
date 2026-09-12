package io.yupiik.ia.agent.core.runtime;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Routes low-level status messages from core components to the active frontend sink. Sinks are
 * tracked as a stack so that if two turns ever overlap (eg. a live turn plus a background
 * re-indexing turn), clearing one turn's sink does not drop the other's; {@code report} always
 * delivers to the most recently installed sink.
 */
@ApplicationScoped
public class StatusReporter {
    private final Deque<Consumer<String>> sinks = new ArrayDeque<>();

    public synchronized void setSink(final Consumer<String> sink) {
        if (sink == null) {
            if (!sinks.isEmpty()) {
                sinks.removeLast();
            }
            return;
        }
        sinks.addLast(sink);
    }

    public void report(final String message) {
        final Consumer<String> s;
        synchronized (this) {
            s = sinks.peekLast();
        }
        if (s != null && message != null && !message.isBlank()) {
            s.accept(message);
        }
    }
}