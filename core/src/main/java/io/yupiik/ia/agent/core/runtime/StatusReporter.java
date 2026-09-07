package io.yupiik.ia.agent.core.runtime;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.function.Consumer;

@ApplicationScoped
public class StatusReporter {
    private volatile Consumer<String> sink;

    public void setSink(final Consumer<String> sink) {
        this.sink = sink;
    }

    public void report(final String message) {
        final var s = sink;
        if (s != null && message != null && !message.isBlank()) {
            s.accept(message);
        }
    }
}
